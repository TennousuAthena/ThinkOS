package protocol

import (
	"encoding/json"
	"testing"
)

func TestTimeSettingsKeepFiveMinuteASCIIValuesAndAPITenGate(t *testing.T) {
	for _, value := range []string{"00:00", "07:05", "12:30", "23:55"} {
		if err := ValidateSettingTime(value, 10); err != nil { t.Fatal(err) }
	}
	for api := 1; api < 10; api++ {
		if ValidateSettingTime("23:55", api) == nil { t.Fatalf("API %d accepted time", api) }
	}
	for _, value := range []string{"", "7:00", "24:00", "12:60", "12:03", "12:00:00", "１２:００", "12:00\n", " 12:00"} {
		if ValidateSettingTime(value, 10) == nil { t.Fatalf("accepted time %q", value) }
	}
	value := SchemaSetting{Type: "time", Label: "时刻", Default: "23:55"}
	encoded, err := json.Marshal(value)
	if err != nil { t.Fatal(err) }
	var decoded SchemaSetting
	if err := json.Unmarshal(encoded, &decoded); err != nil { t.Fatal(err) }
	if decoded.Type != "time" || decoded.Default != "23:55" { t.Fatal("time changed type or value") }
}

func TestFormCategoryIconsKeepFixedNamesAndRequireAPITen(t *testing.T) {
	icon, selected := "monitor", "display"
	link := SchemaFormLink{Key: "section:display", Type: "link", Label: "显示", Icon: &icon}
	nav := SchemaFormNav{Items: []SchemaFormNavItem{{Id: selected, Label: "显示", Icon: &icon}}, Current: &selected}
	for _, descriptor := range []any{link, nav} {
		encoded, err := json.Marshal(descriptor)
		if err != nil { t.Fatal(err) }
		var decoded map[string]any
		if err := json.Unmarshal(encoded, &decoded); err != nil { t.Fatal(err) }
		if item, ok := descriptor.(SchemaFormLink); ok && decoded["icon"] != *item.Icon { t.Fatal("link lost its icon") }
		if _, ok := descriptor.(SchemaFormNav); ok && decoded["items"].([]any)[0].(map[string]any)["icon"] != icon { t.Fatal("navigation lost its icon") }
	}
	for _, navigation := range []bool{false, true} {
		if err := ValidateFormIcon(icon, 10, navigation); err != nil { t.Fatal(err) }
		for api := 1; api < 10; api++ {
			if ValidateFormIcon(icon, api, navigation) == nil { t.Fatalf("API %d accepted a form icon", api) }
		}
		for _, bad := range []string{"", "MONITOR", "../monitor", "missing-vector", "https://icons.invalid/a.svg"} {
			if ValidateFormIcon(bad, 10, navigation) == nil { t.Fatalf("accepted unknown vector %q", bad) }
		}
	}
	legacy, err := json.Marshal(SchemaFormLink{Key: "advanced", Type: "link", Label: "高级"})
	if err != nil { t.Fatal(err) }
	var decoded map[string]any
	if err := json.Unmarshal(legacy, &decoded); err != nil { t.Fatal(err) }
	if _, exists := decoded["icon"]; exists { t.Fatal("legacy links gained an icon placeholder") }
}

func TestUpdatePermissionRequiresAPITen(t *testing.T) {
	if err := ValidatePageRoles(nil, []string{"system.update"}, 10); err != nil {
		t.Fatal(err)
	}
	for api := 1; api < 10; api++ {
		if err := ValidatePageRoles(nil, []string{"system.update"}, api); err == nil {
			t.Fatalf("API %d accepted system.update", api)
		}
	}
}

func TestLauncherRoleRequiresAPINineAndDeclaredCapability(t *testing.T) {
	if err := ValidatePageRoles([]string{"launcher"}, []string{"system.apps"}, 9); err != nil {
		t.Fatal(err)
	}
	if err := ValidatePageRoles([]string{"launcher"}, []string{"system.apps"}, 8); err == nil {
		t.Fatal("API 8 accepted launcher")
	}
	if err := ValidatePageRoles([]string{"launcher"}, nil, 9); err == nil {
		t.Fatal("launcher accepted without system.apps")
	}
	if err := ValidatePageRoles([]string{"home", "sleep"}, nil, 1); err != nil {
		t.Fatal(err)
	}
}

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
	if err := ValidateRenderAPI(decode(legacy), PluginAPI+1); err == nil {
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

func TestTelevisionViewRequiresAPIEight(t *testing.T) {
	var value any
	text := `{"layout":"stage","slots":{"stage":{"type":"tv","fit":"contain","channel":"{{tv}}","tap":"tv"}}}`
	if err := json.Unmarshal([]byte(text), &value); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRenderAPI(value, 8); err != nil {
		t.Errorf("API 8: %v", err)
	}
	for api := 1; api <= 7; api++ {
		if err := ValidateRenderAPI(value, api); err == nil {
			t.Errorf("API %d accepted the television component", api)
		}
	}
}

func TestMusicPlayerViewRequiresAPIEight(t *testing.T) {
	for _, text := range []string{
		`{"layout":"focus","variants":[{"id":"cover","title":"Cover"},{"id":"queue","title":"Queue"}],"slots":{"hero":{"type":"nowplaying","style":"full"}}}`,
		`{"layout":"focus","slots":{"over":{"type":"tabs","items":"{{tabs}}","current":"{{tab}}","tap":"tab"},"hero":{"type":"list","items":"{{items}}","current":"{{current}}","item":{"title":"{{.title}}"}}}}`,
	} {
		var value any
		if err := json.Unmarshal([]byte(text), &value); err != nil {
			t.Fatal(err)
		}
		if err := ValidateRenderAPI(value, 8); err != nil {
			t.Errorf("API 8: %v", err)
		}
		for api := 1; api <= 7; api++ {
			if err := ValidateRenderAPI(value, api); err == nil {
				t.Errorf("API %d accepted the music player vocabulary", api)
			}
		}
	}
}

func TestFormNavigationRequiresAPINine(t *testing.T) {
	var value any
	if err := json.Unmarshal([]byte(`{"layout":"form","slots":{"body":{"type":"form","path":"fields","nav":"settingsNav"}}}`), &value); err != nil {
		t.Fatal(err)
	}
	if err := ValidateRenderAPI(value, 9); err != nil {
		t.Fatal(err)
	}
	for api := 1; api <= 8; api++ {
		if ValidateRenderAPI(value, api) == nil {
			t.Fatalf("API %d accepted the form navigation contract", api)
		}
	}
}

func TestGridRetainsItsExistingAPIEightVocabulary(t *testing.T) {
	value := map[string]any{"type": "grid", "items": "items", "item": map[string]any{"title": "{{.title}}", "image": "{{.image}}", "icon": "apps"}}
	for _, api := range []int{8, 9} {
		if err := ValidateRenderAPI(value, api); err != nil {
			t.Fatal(err)
		}
	}
	if ValidateRenderAPI(value, 7) == nil {
		t.Fatal("API 7 accepted grid")
	}
}
