package protocol

import (
    "encoding/json"
    "testing"
)

func TestRenderAPIVocabularyVersions(t *testing.T) {
    legacy := `{"layout":"focus","slots":{"hero":{"type":"clock"}}}`
    modern := []string{
        `{"layout":"focus","slots":{"hero":{"type":"progress","value":0.5}}}`,
        `{"layout":"focus","slots":{"hero":{"type":"text","value":"Tap","tap":"open"}}}`,
        `{"layout":"focus","slots":{"hero":{"children":[{"type":"text","value":"Group"}]}}}`,
        `{"layout":"stage","slots":{"stage":{"type":"daylight","path":"sun"}}}`,
    }
    decode := func(text string) any {
        var value any
        if err := json.Unmarshal([]byte(text), &value); err != nil { t.Fatal(err) }
        return value
    }
    for _, api := range []int{1, 2} {
        if err := ValidateRenderAPI(decode(legacy), api); err != nil { t.Fatalf("legacy API %d: %v", api, err) }
    }
    for _, text := range modern {
        if err := ValidateRenderAPI(decode(text), 2); err != nil { t.Errorf("API 2: %v", err) }
        if err := ValidateRenderAPI(decode(text), 1); err == nil { t.Errorf("API 1 accepted new vocabulary: %s", text) }
    }
    if err := ValidateRenderAPI(decode(legacy), 3); err == nil { t.Error("accepted unsupported API") }
}
