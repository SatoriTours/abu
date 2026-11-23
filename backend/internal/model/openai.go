// Package model defines provider-independent completion contracts.
package model

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"
)

type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

const DefaultCompletionTimeout = 45 * time.Second

type Completer interface {
	Complete(context.Context, []Message) (string, error)
}

type Config struct {
	BaseURL string
	Model   string
	APIKey  string
}

type OpenAI struct {
	endpoint string
	model    string
	key      string
	client   *http.Client
}

var _ Completer = (*OpenAI)(nil)

// NewOpenAI supports non-streaming Chat Completions. It makes no network request.
func NewOpenAI(cfg Config) (*OpenAI, error) {
	base, err := url.Parse(cfg.BaseURL)
	if err != nil || base.Hostname() == "" || base.User != nil || base.RawQuery != "" || base.ForceQuery || base.Fragment != "" {
		return nil, errors.New("invalid model base URL")
	}
	local := base.Hostname() == "localhost"
	if ip := net.ParseIP(base.Hostname()); ip != nil {
		local = ip.IsLoopback()
	}
	if base.Scheme != "https" && !(base.Scheme == "http" && local) {
		return nil, errors.New("model endpoint requires HTTPS except on loopback")
	}
	if strings.TrimSpace(cfg.Model) == "" || strings.TrimSpace(cfg.APIKey) == "" || strings.ContainsAny(cfg.APIKey, "\r\n") {
		return nil, errors.New("model and API key are required")
	}
	base.Path = strings.TrimRight(base.Path, "/") + "/chat/completions"
	base.RawPath = ""
	return &OpenAI{
		endpoint: base.String(), model: cfg.Model, key: cfg.APIKey,
		client: &http.Client{Timeout: DefaultCompletionTimeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	}, nil
}

func (c *OpenAI) Complete(ctx context.Context, messages []Message) (string, error) {
	if len(messages) == 0 || len(messages) > 200 {
		return "", errors.New("invalid message count")
	}
	for _, m := range messages {
		if m.Role != "system" && m.Role != "user" && m.Role != "assistant" {
			return "", errors.New("unsupported message role")
		}
		if strings.TrimSpace(m.Content) == "" || !utf8.ValidString(m.Content) || utf8.RuneCountInString(m.Content) > 4000 {
			return "", errors.New("invalid message content")
		}
	}
	payload, err := json.Marshal(struct {
		Model    string    `json:"model"`
		Messages []Message `json:"messages"`
		Stream   bool      `json:"stream"`
	}{c.model, messages, false})
	if err != nil {
		return "", errors.New("could not encode model request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(payload))
	if err != nil {
		return "", errors.New("could not create model request")
	}
	req.Header.Set("Authorization", "Bearer "+c.key)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	client := c.client
	if _, bounded := ctx.Deadline(); bounded {
		// The server supplies a finite per-purpose deadline that covers both headers
		// and body. Do not impose a second short limit on draft generation.
		withDeadline := *client
		withDeadline.Timeout = 0
		client = &withDeadline
	}
	response, err := client.Do(req)
	if err != nil {
		if deadlineErr := completionDeadlineError(ctx, err); deadlineErr != nil {
			return "", deadlineErr
		}
		return "", errors.New("model connection failed or timed out")
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("model returned HTTP %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 1024*1024+1))
	if err != nil {
		if deadlineErr := completionDeadlineError(ctx, err); deadlineErr != nil {
			return "", deadlineErr
		}
		return "", errors.New("could not read model response")
	}
	if len(body) > 1024*1024 {
		return "", errors.New("model response exceeds size limit")
	}
	var result struct {
		Choices []struct {
			Message struct {
				Role      string            `json:"role"`
				Content   string            `json:"content"`
				ToolCalls []json.RawMessage `json:"tool_calls"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
	}
	if json.Unmarshal(body, &result) != nil || len(result.Choices) != 1 {
		return "", errors.New("invalid model response")
	}
	choice := result.Choices[0]
	if choice.FinishReason != "stop" || len(choice.Message.ToolCalls) > 0 || choice.Message.Role != "assistant" {
		return "", errors.New("model did not return a complete text reply")
	}
	if strings.TrimSpace(choice.Message.Content) == "" {
		return "", errors.New("model returned an empty reply")
	}
	return choice.Message.Content, nil
}

func completionDeadlineError(ctx context.Context, err error) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return context.DeadlineExceeded
	}
	return nil
}
