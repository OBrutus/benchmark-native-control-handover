package core

import (
	"strings"
	"testing"
	"time"
)

func fixedGreeter(t *testing.T) *Greeter {
	t.Helper()
	g := NewGreeter()
	g.now = func() time.Time { return time.Date(2026, 8, 6, 12, 0, 0, 0, time.UTC) }
	return g
}

func TestGreetSuccess(t *testing.T) {
	g := fixedGreeter(t)

	got, err := g.Greet("Aniket", "en")
	if err != nil {
		t.Fatalf("Greet: unexpected error: %v", err)
	}
	if want := "Hello, Aniket!"; got.Message != want {
		t.Errorf("Message = %q, want %q", got.Message, want)
	}
	if got.Language != "en" {
		t.Errorf("Language = %q, want %q", got.Language, "en")
	}
	if got.At.IsZero() {
		t.Error("At is zero, want the injected clock value")
	}
}

func TestGreetTrimsName(t *testing.T) {
	g := fixedGreeter(t)

	got, err := g.Greet("  Aniket  ", "en")
	if err != nil {
		t.Fatalf("Greet: unexpected error: %v", err)
	}
	if want := "Hello, Aniket!"; got.Message != want {
		t.Errorf("Message = %q, want %q", got.Message, want)
	}
}

func TestGreetEmptyName(t *testing.T) {
	g := fixedGreeter(t)

	if _, err := g.Greet("   ", "en"); err == nil {
		t.Fatal("Greet(\"   \"): want error, got nil")
	}
}

func TestGreetUnknownLanguage(t *testing.T) {
	g := fixedGreeter(t)

	_, err := g.Greet("Aniket", "xx")
	if err == nil {
		t.Fatal("Greet(lang=xx): want error, got nil")
	}
	// The error should be actionable: it lists what is available.
	if !strings.Contains(err.Error(), "en") {
		t.Errorf("error %q does not mention the supported languages", err)
	}
}

func TestLanguagesSorted(t *testing.T) {
	got := NewGreeter().Languages()

	if len(got) == 0 {
		t.Fatal("Languages() is empty")
	}
	for i := 1; i < len(got); i++ {
		if got[i-1] >= got[i] {
			t.Fatalf("Languages() not sorted/unique: %v", got)
		}
	}
}
