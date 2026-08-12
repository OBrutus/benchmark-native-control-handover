// Package core holds the real application logic.
//
// It is written as ordinary, idiomatic Go and knows nothing about Android,
// JNI, or gomobile's type restrictions. Maps, slices of structs, time.Time
// and multiple return values are all fine in here — none of them ever cross
// the language boundary directly. That is the whole point of keeping the
// facade in a separate package (see ../mobile).
package core

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

// Greeting is a result value. Note the struct tags: the facade serialises
// this to JSON, because gomobile cannot pass a struct like this to Kotlin.
type Greeting struct {
	Message  string    `json:"message"`
	Language string    `json:"language"`
	Name     string    `json:"name"`
	At       time.Time `json:"at"`
}

// Greeter produces greetings in a handful of languages.
type Greeter struct {
	// A map — one of several types gomobile refuses to bind. Perfectly
	// normal Go, and invisible from Kotlin's side.
	templates map[string]string
	now       func() time.Time
}

// NewGreeter returns a Greeter with the built-in language set.
func NewGreeter() *Greeter {
	return &Greeter{
		templates: map[string]string{
			"en": "Hello, %s!",
			"hi": "नमस्ते, %s!",
			"ja": "こんにちは、%sさん!",
			"fr": "Bonjour, %s !",
			"de": "Hallo, %s!",
		},
		now: time.Now,
	}
}

// Greet builds a greeting for name in the given language code.
//
// It returns a plain Go error, which the facade lets gomobile translate into
// a Java exception.
func (g *Greeter) Greet(name, lang string) (Greeting, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return Greeting{}, fmt.Errorf("name must not be empty")
	}

	tmpl, ok := g.templates[lang]
	if !ok {
		return Greeting{}, fmt.Errorf("unsupported language %q (have: %s)",
			lang, strings.Join(g.Languages(), ", "))
	}

	return Greeting{
		Message:  fmt.Sprintf(tmpl, name),
		Language: lang,
		Name:     name,
		At:       g.now(),
	}, nil
}

// Languages lists the supported language codes, sorted.
//
// []string is another type that cannot cross the gomobile boundary, so the
// facade flattens it.
func (g *Greeter) Languages() []string {
	out := make([]string, 0, len(g.templates))
	for code := range g.templates {
		out = append(out, code)
	}
	sort.Strings(out)
	return out
}
