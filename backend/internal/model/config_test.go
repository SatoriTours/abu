package model

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadConfigFile(t *testing.T) {
	t.Setenv("ABU_MODEL_API_KEY", "")
	t.Setenv("ABU_MODEL_BASE_URL", "")
	t.Setenv("ABU_MODEL_NAME", "")
	path := filepath.Join(t.TempDir(), "model.env")
	if err := os.WriteFile(path, []byte("# secret file\nABU_MODEL_API_KEY='test-secret'\n"), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, enabled, err := LoadConfig(path)
	if err != nil || !enabled || cfg.APIKey != "test-secret" || cfg.BaseURL != "https://magpie.home.jimxl.com/v1" || cfg.Model != "group/deepseek-flash-4.1" {
		t.Fatal("configuration did not load correctly")
	}
}
func TestEmptyConfigDoesNotEnableModel(t *testing.T) {
	t.Setenv("ABU_MODEL_API_KEY", "")
	_, enabled, err := LoadConfig("")
	if err != nil || enabled {
		t.Fatal("unexpected activation")
	}
}
func TestConfigRejectsPlaceholderAndUnknownEntries(t *testing.T) {
	t.Setenv("ABU_MODEL_API_KEY", "")
	for _, body := range []string{"ABU_MODEL_API_KEY=REPLACE_WITH_YOUR_API_KEY", "ABU_MODEL_API_KEY=k\nUNEXPECTED=secret", "ABU_MODEL_API_KEY=k\nABU_MODEL_API_KEY=other"} {
		path := filepath.Join(t.TempDir(), "model.env")
		if err := os.WriteFile(path, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
		if _, _, err := LoadConfig(path); err == nil {
			t.Fatal("invalid config accepted")
		}
	}
}
