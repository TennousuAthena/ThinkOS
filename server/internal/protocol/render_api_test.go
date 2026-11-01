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
		if err := json.Unmarshal([]byte(text), &value); err != nil {
			t.Fatal(err)
		}
		return value
	}
	for _, api := range []int{1, 2, 3, 4, 5} {
		if err := ValidateRenderAPI(decode(legacy), api); err != nil {
			t.Fatalf("legacy API %d: %v", api, err)
		}
	}
	for _, text := range modern {
		if err := ValidateRenderAPI(decode(text), 2); err != nil {
			t.Errorf("API 2: %v", err)
		}
		if err := ValidateRenderAPI(decode(text), 1); err == nil {
			t.Errorf("API 1 accepted new vocabulary: %s", text)
		}
	}
	for _, text := range []string{
		`{"layout":"stage","slots":{"stage":{"type":"carousel","items":"photos","interval":30,"duration":800,"fit":"pan"}}}`,
		`{"template":"glance","children":[{"type":"image","src":"{{cover}}","fit":"contain"}]}`,
	} {
		if err := ValidateRenderAPI(decode(text), 3); err != nil {
			t.Errorf("API 3: %v", err)
		}
		if err := ValidateRenderAPI(decode(text), 2); err == nil {
			t.Error("API 2 accepted image vocabulary")
		}
	}
	if err := ValidateRenderAPI(decode(legacy), 8); err == nil {
		t.Error("accepted unsupported API")
	}
}

func TestAeroRequiresAPIFive(t *testing.T) {
	var value any
	if err := json.Unmarshal([]byte(`{"layout":"aero","slots":{"windows":[{"widget":"clock.desktop"}]}}`), &value); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRenderAPI(value, 5); err != nil {
		t.Fatal(err)
	}
	for api := 1; api <= 4; api++ {
		if ValidateRenderAPI(value, api) == nil {
			t.Fatalf("API %d accepted Aero", api)
		}
	}
}

func TestTilesRequireAPIFour(t *testing.T) {
	for _, text := range []string{
		`{"template":"tile","faces":[{"label":"Weather"}]}`,
		`{"layout":"tiles","slots":{"tiles":[{"widget":"clock.tile","size":"wide"}]}}`,
	} {
		var value any
		if err := json.Unmarshal([]byte(text), &value); err != nil {
			t.Fatal(err)
		}
		if err := ValidateRenderAPI(value, 4); err != nil {
			t.Fatal(err)
		}
		for api := 1; api <= 3; api++ {
			if ValidateRenderAPI(value, api) == nil {
				t.Fatalf("API %d accepted tile: %s", api, text)
			}
		}
	}
}

func TestTileImageRequiresAPI6(t *testing.T) {
	var value any
	if err := json.Unmarshal([]byte(`{"template":"tile","image":{"type":"image","src":"{{a}}"},"faces":[{"label":"x"}]}`), &value); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRenderAPI(value, 6); err != nil {
		t.Error(err)
	}
	if ValidateRenderAPI(value, 5) == nil {
		t.Error("API 5 accepted a tile image")
	}
}

func TestTileFaceContentRequiresAPI6(t *testing.T) {
	var value any
	if err := json.Unmarshal([]byte(`{"template":"tile","faces":[{"label":"x","content":{"type":"clock","style":"analog"}}]}`), &value); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRenderAPI(value, 6); err != nil {
		t.Error(err)
	}
	if ValidateRenderAPI(value, 5) == nil {
		t.Error("API 5 accepted a tile face content")
	}
}

func TestSlotWidgetReferencesRequireAPI6(t *testing.T) {
	decode := func(text string) any {
		var value any
		if err := json.Unmarshal([]byte(text), &value); err != nil {
			t.Fatal(err)
		}
		return value
	}
	page := decode(`{"layout":"focus","slots":{"hero":{"widget":"gallery.preview"}}}`)
	if err := ValidateRenderAPI(page, 6); err != nil {
		t.Error(err)
	}
	if ValidateRenderAPI(page, 5) == nil {
		t.Error("API 5 accepted a widget reference in a slot")
	}
	stacks := decode(`{"layout":"stacks","slots":{"left":[{"widget":"gallery.preview"}],"right":[{"widget":"clock.now"}]}}`)
	if err := ValidateRenderAPI(stacks, 1); err != nil {
		t.Error(err)
	}
}

func TestMediaComponentsRequireAPISeven(t *testing.T) {
	for _, text := range []string{
		`{"layout":"stage","slots":{"stage":{"type":"video","fit":"contain"}}}`,
		`{"layout":"focus","slots":{"hero":{"children":[{"type":"seekbar"},{"type":"controls","mode":"seek"}]}}}`,
	} {
		var value any
		if err := json.Unmarshal([]byte(text), &value); err != nil {
			t.Fatal(err)
		}
		if err := ValidateRenderAPI(value, 7); err != nil {
			t.Errorf("API 7: %v", err)
		}
		for api := 1; api <= 6; api++ {
			if err := ValidateRenderAPI(value, api); err == nil {
				t.Errorf("API %d accepted a media component", api)
			}
		}
	}
}
