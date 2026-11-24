// Package model defines provider-independent completion contracts.
package model

import (
	"bufio"
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

// StreamCompleter is an optional extension: providers that can report reply text
// incrementally. Complete stays the authoritative whole-body contract.
type StreamCompleter interface {
	Completer
	CompleteStream(ctx context.Context, messages []Message, onDelta func(delta string) error) (string, error)
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

var (
	_ Completer       = (*OpenAI)(nil)
	_ StreamCompleter = (*OpenAI)(nil)
)

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

func validateMessages(messages []Message) error {
	if len(messages) == 0 || len(messages) > 200 {
		return errors.New("invalid message count")
	}
	for _, m := range messages {
		if m.Role != "system" && m.Role != "user" && m.Role != "assistant" {
			return errors.New("unsupported message role")
		}
		if strings.TrimSpace(m.Content) == "" || !utf8.ValidString(m.Content) || utf8.RuneCountInString(m.Content) > 4000 {
			return errors.New("invalid message content")
		}
	}
	return nil
}

func (c *OpenAI) Complete(ctx context.Context, messages []Message) (string, error) {
	if err := validateMessages(messages); err != nil {
		return "", err
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
	// Validate the raw bytes first: encoding/json would silently replace bad bytes with U+FFFD.
	if !utf8.Valid(body) {
		return "", errors.New("invalid model response encoding")
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

const maxStreamBody = 4 * 1024 * 1024

// CompleteStream reads an SSE chat-completions stream. It reports each non-empty text
// delta to onDelta and returns the accumulated text only when the stream completed
// normally; a cancelled, truncated or oversized stream is never reported as success.
func (c *OpenAI) CompleteStream(ctx context.Context, messages []Message, onDelta func(delta string) error) (string, error) {
	if err := validateMessages(messages); err != nil {
		return "", err
	}
	payload, err := json.Marshal(struct {
		Model    string    `json:"model"`
		Messages []Message `json:"messages"`
		Stream   bool      `json:"stream"`
	}{c.model, messages, true})
	if err != nil {
		return "", errors.New("could not encode model request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(payload))
	if err != nil {
		return "", errors.New("could not create model request")
	}
	req.Header.Set("Authorization", "Bearer "+c.key)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")
	client := c.client
	if _, bounded := ctx.Deadline(); bounded {
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
	var content strings.Builder
	reader := bufio.NewReader(io.LimitReader(response.Body, maxStreamBody+1))
	read := int64(0)
	finishReason := ""
	// One SSE event is framed by a blank line and may carry several data lines.
	var dataLines []string
	dispatch := func() (bool, error) {
		if len(dataLines) == 0 {
			return false, nil
		}
		raw := strings.Join(dataLines, "\n")
		dataLines = dataLines[:0]
		if raw == "[DONE]" {
			return true, nil
		}
		if strings.TrimSpace(raw) == "" {
			return false, nil
		}
		// Validate the raw event bytes first: encoding/json would silently replace bad
		// bytes with U+FFFD, which would let a corrupt stream pass as valid text.
		if !utf8.ValidString(raw) {
			return false, errors.New("invalid model stream encoding")
		}
		reason, appended, err := consumeChunk(raw)
		if err != nil {
			return false, err
		}
		if reason != "" {
			finishReason = reason
		}
		if appended == "" {
			return false, nil
		}
		content.WriteString(appended)
		if !utf8.ValidString(content.String()) {
			return false, errors.New("invalid model stream content")
		}
		if utf8.RuneCountInString(content.String()) > 4000 {
			return false, errors.New("model stream exceeds length limit")
		}
		if onDelta != nil {
			if err := onDelta(appended); err != nil {
				return false, err
			}
		}
		return false, nil
	}
	stopped := false
	for {
		line, readErr := reader.ReadString('\n')
		read += int64(len(line))
		if read > maxStreamBody {
			return "", errors.New("model stream exceeds size limit")
		}
		if readErr != nil && readErr != io.EOF {
			if deadlineErr := completionDeadlineError(ctx, readErr); deadlineErr != nil {
				return "", deadlineErr
			}
			return "", errors.New("could not read model stream")
		}
		// Bytes without a trailing newline are the remains of an unfinished line.
		truncated := line != "" && !strings.HasSuffix(line, "\n")
		if !truncated {
			frame := strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r")
			// Only a real blank line ends an event. A line holding blanks or tabs is not
			// blank, so it must not dispatch a frame that is still open. An empty read at
			// EOF must never dispatch whatever is still buffered either.
			if line != "" && frame == "" {
				stop, err := dispatch()
				if err != nil {
					return "", err
				}
				stopped = stop
			} else if data, ok := sseData(frame); ok {
				dataLines = append(dataLines, data)
			}
		}
		if readErr != nil {
			// EOF, or a stream that ended without a blank line: a trailing partial line or an
			// undispatched event is an incomplete stream, never a complete event.
			if truncated || len(dataLines) > 0 {
				return "", errors.New("incomplete model stream")
			}
			break
		}
		if stopped {
			break
		}
	}
	if deadlineErr := completionDeadlineError(ctx, nil); deadlineErr != nil {
		return "", deadlineErr
	}
	if finishReason != "stop" {
		return "", errors.New("model did not return a complete text reply")
	}
	text := content.String()
	if strings.TrimSpace(text) == "" {
		return "", errors.New("model returned an empty reply")
	}
	return text, nil
}

// sseData returns the value of an SSE data line. Blank lines, comments and other
// fields carry no reply text and are ignored.
func sseData(line string) (string, bool) {
	if !strings.HasPrefix(line, "data:") {
		return "", false
	}
	return strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "), true
}

// consumeChunk decodes one SSE JSON chunk and returns its finish reason and text delta.
func consumeChunk(data string) (string, string, error) {
	var chunk struct {
		Choices []struct {
			Delta struct {
				Role      string            `json:"role"`
				Content   string            `json:"content"`
				ToolCalls []json.RawMessage `json:"tool_calls"`
			} `json:"delta"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
	}
	if json.Unmarshal([]byte(data), &chunk) != nil || len(chunk.Choices) > 1 {
		return "", "", errors.New("invalid model stream chunk")
	}
	if len(chunk.Choices) == 0 {
		// Trailing usage-only chunks are valid and carry no text.
		return "", "", nil
	}
	choice := chunk.Choices[0]
	if len(choice.Delta.ToolCalls) > 0 || (choice.Delta.Role != "" && choice.Delta.Role != "assistant") {
		return "", "", errors.New("model did not return a complete text reply")
	}
	return choice.FinishReason, choice.Delta.Content, nil
}
