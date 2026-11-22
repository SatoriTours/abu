package model

import (
	"bufio"
	"errors"
	"os"
	"strings"
)

// LoadConfig parses only known literal KEY=value entries, never shell code.
// No file is read unless explicitly selected by the caller.
func LoadConfig(path string) (Config, bool, error) {
	values := map[string]string{
		"ABU_MODEL_BASE_URL": "https://magpie.home.jimxl.com/v1",
		"ABU_MODEL_NAME":     "group/deepseek-flash-4.1",
		"ABU_MODEL_API_KEY":  "",
	}
	if path != "" {
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return Config{}, false, errors.New("model config must be a private regular file (mode 600)")
		}
		f, err := os.Open(path)
		if err != nil {
			return Config{}, false, errors.New("cannot open model config")
		}
		defer f.Close()
		scanner := bufio.NewScanner(f)
		seen := map[string]bool{}
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			key, value, ok := strings.Cut(line, "=")
			key = strings.TrimSpace(key)
			value = strings.TrimSpace(value)
			if _, known := values[key]; !ok || !known || seen[key] {
				return Config{}, false, errors.New("invalid or duplicate model config entry")
			}
			seen[key] = true
			if len(value) >= 2 && ((value[0] == '"' && value[len(value)-1] == '"') || (value[0] == '\'' && value[len(value)-1] == '\'')) {
				value = value[1 : len(value)-1]
			}
			values[key] = value
		}
		if scanner.Err() != nil {
			return Config{}, false, errors.New("cannot parse model config")
		}
	}
	for key := range values {
		if value := os.Getenv(key); value != "" {
			values[key] = value
		}
	}
	cfg := Config{BaseURL: values["ABU_MODEL_BASE_URL"], Model: values["ABU_MODEL_NAME"], APIKey: values["ABU_MODEL_API_KEY"]}
	if strings.TrimSpace(cfg.APIKey) == "" {
		if path != "" {
			return Config{}, false, errors.New("model config has no API key")
		}
		return cfg, false, nil
	}
	if cfg.APIKey == "REPLACE_WITH_YOUR_API_KEY" {
		return Config{}, false, errors.New("replace the model API key placeholder")
	}
	if _, err := NewOpenAI(cfg); err != nil {
		return Config{}, false, err
	}
	return cfg, true, nil
}
