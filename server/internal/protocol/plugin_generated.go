// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_go.py.
package protocol

import "fmt"

type SchemaPluginCatalog struct {
	Plugins []SchemaPluginIndex `json:"plugins"`
}

type SchemaPluginIndex struct {
	Id string `json:"id"`
	Version string `json:"version"`
	Digest string `json:"digest"`
	Files []SchemaPluginFile `json:"files"`
}

type SchemaPluginFile struct {
	Path string `json:"path"`
	Sha256 string `json:"sha256"`
	Size int `json:"size"`
}

type SchemaText struct {
	Type string `json:"type"`
	Value string `json:"value"`
	Style *string `json:"style,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaIcon struct {
	Type string `json:"type"`
	Name string `json:"name"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaClock struct {
	Type string `json:"type"`
	Style *string `json:"style,omitempty"`
	Format *string `json:"format,omitempty"`
	Sun *string `json:"sun,omitempty"`
	Zones []SchemaClockZone `json:"zones,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaClockZone struct {
	Label *string `json:"label,omitempty"`
	Timezone string `json:"timezone"`
}

type SchemaWidgetReference struct {
	Widget string `json:"widget"`
}

type SchemaStacksView struct {
	Variants []SchemaVariant `json:"variants,omitempty"`
	Mood *string `json:"mood,omitempty"`
	Layout string `json:"layout"`
	Slots map[string]any `json:"slots"`
}

type SchemaVariant struct {
	Id string `json:"id"`
	Title string `json:"title"`
}

type SchemaFocusView struct {
	Variants []SchemaVariant `json:"variants,omitempty"`
	Mood *string `json:"mood,omitempty"`
	Layout string `json:"layout"`
	Slots map[string]any `json:"slots"`
}

type SchemaGlanceView struct {
	Template string `json:"template"`
	Children []any `json:"children"`
	Relevance any `json:"relevance,omitempty"`
}

type SchemaForm struct {
	Type string `json:"type"`
	Path string `json:"path"`
	Title *string `json:"title,omitempty"`
	Done *string `json:"done,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Nav *string `json:"nav,omitempty"`
	Subtitle *string `json:"subtitle,omitempty"`
	Primary *string `json:"primary,omitempty"`
	Secondary *string `json:"secondary,omitempty"`
	Back *string `json:"back,omitempty"`
}

type SchemaFormView struct {
	Layout string `json:"layout"`
	Slots map[string]any `json:"slots"`
}

type SchemaProgress struct {
	Type string `json:"type"`
	Style *string `json:"style,omitempty"`
	Value any `json:"value"`
	Max any `json:"max,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaChart struct {
	Type string `json:"type"`
	Style *string `json:"style,omitempty"`
	Values any `json:"values"`
	Min any `json:"min,omitempty"`
	Max any `json:"max,omitempty"`
	Highlight any `json:"highlight,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaListRow struct {
	Icon *string `json:"icon,omitempty"`
	Title string `json:"title"`
	Subtitle *string `json:"subtitle,omitempty"`
	Trailing *string `json:"trailing,omitempty"`
	Progress any `json:"progress,omitempty"`
	Max any `json:"max,omitempty"`
}

type SchemaList struct {
	Type string `json:"type"`
	Items string `json:"items"`
	Item SchemaListRow `json:"item"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Current any `json:"current,omitempty"`
}

type SchemaDaylight struct {
	Type string `json:"type"`
	Path string `json:"path"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaMoon struct {
	Type string `json:"type"`
	Path string `json:"path"`
	Hemisphere *string `json:"hemisphere,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaComponentGroup struct {
	Children []any `json:"children"`
}

type SchemaSplitView struct {
	Layout string `json:"layout"`
	Variants []SchemaVariant `json:"variants,omitempty"`
	Mood *string `json:"mood,omitempty"`
	Slots map[string]any `json:"slots"`
}

type SchemaStageView struct {
	Layout string `json:"layout"`
	Variants []SchemaVariant `json:"variants,omitempty"`
	Mood *string `json:"mood,omitempty"`
	Slots map[string]any `json:"slots"`
}

type SchemaControls struct {
	Type string `json:"type"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Mode *string `json:"mode,omitempty"`
}

type SchemaVideo struct {
	Type string `json:"type"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Fit *string `json:"fit,omitempty"`
}

type SchemaSeekbar struct {
	Type string `json:"type"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaImage struct {
	Type string `json:"type"`
	Src string `json:"src"`
	Fit *string `json:"fit,omitempty"`
	Pan any `json:"pan,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Interval any `json:"interval,omitempty"`
	Pan_speed any `json:"pan_speed,omitempty"`
	Pan_seconds any `json:"pan_seconds,omitempty"`
	Pan_direction *string `json:"pan_direction,omitempty"`
}

type SchemaCarousel struct {
	Type string `json:"type"`
	Items string `json:"items"`
	Interval any `json:"interval"`
	Duration any `json:"duration"`
	Transition *string `json:"transition,omitempty"`
	Fit *string `json:"fit,omitempty"`
	Pan any `json:"pan,omitempty"`
	Shuffle any `json:"shuffle,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Pan_speed any `json:"pan_speed,omitempty"`
	Caption *string `json:"caption,omitempty"`
	Caption_hold any `json:"caption_hold,omitempty"`
	Pan_seconds any `json:"pan_seconds,omitempty"`
	Pan_direction *string `json:"pan_direction,omitempty"`
}

type SchemaTileReference struct {
	Widget string `json:"widget"`
	Size string `json:"size"`
	Color *string `json:"color,omitempty"`
}

type SchemaTileFace struct {
	Icon *string `json:"icon,omitempty"`
	Value *string `json:"value,omitempty"`
	Label string `json:"label"`
	Detail *string `json:"detail,omitempty"`
	Content any `json:"content,omitempty"`
}

type SchemaTileView struct {
	Template string `json:"template"`
	Color *string `json:"color,omitempty"`
	Faces []SchemaTileFace `json:"faces"`
	Badge *string `json:"badge,omitempty"`
	Content any `json:"content,omitempty"`
	Image SchemaImage `json:"image,omitempty"`
}

type SchemaTilesView struct {
	Layout string `json:"layout"`
	Slots SchemaTilesSlots `json:"slots"`
	Variants []SchemaVariant `json:"variants,omitempty"`
	Mood *string `json:"mood,omitempty"`
}

type SchemaTilesSlots struct {
	Tiles []SchemaTileReference `json:"tiles"`
}

type SchemaAero struct {
	Layout string `json:"layout"`
	Slots SchemaAeroSlots `json:"slots"`
}

type SchemaAeroSlots struct {
	Windows []SchemaWidgetReference `json:"windows"`
}

type SchemaNowPlaying struct {
	Type string `json:"type"`
	Style *string `json:"style,omitempty"`
	Idle *string `json:"idle,omitempty"`
	Notice *string `json:"notice,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaTv struct {
	Type string `json:"type"`
	Channel *string `json:"channel,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Fit *string `json:"fit,omitempty"`
}

type SchemaTabs struct {
	Type string `json:"type"`
	Items string `json:"items"`
	Current any `json:"current,omitempty"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaSlider struct {
	Type string `json:"type"`
	Level string `json:"level"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaToggle struct {
	Type string `json:"type"`
	Icon string `json:"icon"`
	Label string `json:"label"`
	On any `json:"on,omitempty"`
	Tap string `json:"tap"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaGridCell struct {
	Icon *string `json:"icon,omitempty"`
	Image *string `json:"image,omitempty"`
	Title string `json:"title"`
}

type SchemaGrid struct {
	Type string `json:"type"`
	Items string `json:"items"`
	Item SchemaGridCell `json:"item"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
}

type SchemaPanelView struct {
	Layout string `json:"layout"`
	Slots map[string]any `json:"slots"`
}

type SchemaFormLink struct {
	Key string `json:"key"`
	Type string `json:"type"`
	Label string `json:"label"`
	Display *string `json:"display,omitempty"`
	Icon *string `json:"icon,omitempty"`
	Group *string `json:"group,omitempty"`
	Confirm *string `json:"confirm,omitempty"`
}

type SchemaFormNav struct {
	Items []SchemaFormNavItem `json:"items"`
	Current *string `json:"current,omitempty"`
}

type SchemaFormNavItem struct {
	Id string `json:"id"`
	Label string `json:"label"`
	Icon *string `json:"icon,omitempty"`
}

type SchemaManifest struct {
	Id string `json:"id"`
	Name string `json:"name"`
	Version string `json:"version"`
	Api int `json:"api"`
	Icon *string `json:"icon,omitempty"`
	Main *string `json:"main,omitempty"`
	Permissions []string `json:"permissions,omitempty"`
	Settings map[string]any `json:"settings,omitempty"`
	Pages []SchemaPage `json:"pages,omitempty"`
	Widgets []SchemaWidget `json:"widgets,omitempty"`
	Commands []SchemaCommand `json:"commands,omitempty"`
	Exports map[string]any `json:"exports,omitempty"`
	Uses map[string]any `json:"uses,omitempty"`
	Calls []string `json:"calls,omitempty"`
	Apps []SchemaApp `json:"apps,omitempty"`
	Related []string `json:"related,omitempty"`
}

type SchemaPage struct {
	Id string `json:"id"`
	View string `json:"view"`
	Title string `json:"title"`
	Roles []string `json:"roles,omitempty"`
	Priority *int `json:"priority,omitempty"`
	Live *bool `json:"live,omitempty"`
	Variants []SchemaVariant `json:"variants,omitempty"`
}

type SchemaWidget struct {
	Id string `json:"id"`
	View string `json:"view"`
	Opens *string `json:"opens,omitempty"`
	Alternates SchemaWidgetAlternates `json:"alternates,omitempty"`
}

type SchemaWidgetAlternates struct {
	Glance *string `json:"glance,omitempty"`
	Tile *string `json:"tile,omitempty"`
}

type SchemaSetting struct {
	Type string `json:"type"`
	Label string `json:"label"`
	Default any `json:"default"`
	Help *string `json:"help,omitempty"`
	Group *string `json:"group,omitempty"`
	Choices []SchemaSettingChoice `json:"choices,omitempty"`
	Source *string `json:"source,omitempty"`
	AllowEmpty *bool `json:"allowEmpty,omitempty"`
	Min *float64 `json:"min,omitempty"`
	Max *float64 `json:"max,omitempty"`
	Step *float64 `json:"step,omitempty"`
	Unit *string `json:"unit,omitempty"`
	MaxLength *int `json:"maxLength,omitempty"`
	Secret *bool `json:"secret,omitempty"`
	Hidden *bool `json:"hidden,omitempty"`
	Warn *string `json:"warn,omitempty"`
	Placeholder *string `json:"placeholder,omitempty"`
	Level *string `json:"level,omitempty"`
	Depends any `json:"depends,omitempty"`
	Format *string `json:"format,omitempty"`
	Zero *string `json:"zero,omitempty"`
	Presets []float64 `json:"presets,omitempty"`
}

type SchemaSettingChoice struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

type SchemaCommand struct {
	Id string `json:"id"`
	Phrases []string `json:"phrases,omitempty"`
	Gesture *string `json:"gesture,omitempty"`
	Public *bool `json:"public,omitempty"`
	Cooldown *float64 `json:"cooldown,omitempty"`
}

type SchemaExport struct {
	Path string `json:"path"`
	Shape string `json:"shape"`
	Requires []string `json:"requires,omitempty"`
}

type SchemaUse struct {
	Export string `json:"export"`
	Shape string `json:"shape"`
}

type SchemaApp struct {
	Id string `json:"id"`
	Page string `json:"page"`
	Title string `json:"title"`
	Icon *string `json:"icon,omitempty"`
	Listed *bool `json:"listed,omitempty"`
}

const PluginAPI = 10

var renderComponentTypes = map[string]int{"text": 1, "icon": 1, "clock": 1, "form": 1, "progress": 2, "chart": 2, "list": 2, "daylight": 2, "moon": 2, "image": 3, "carousel": 3, "controls": 7, "video": 7, "seekbar": 7, "nowplaying": 8, "tv": 8, "tabs": 8, "slider": 8, "toggle": 8, "grid": 8}

var renderLayouts = map[string]int{"stacks": 1, "focus": 1, "form": 1, "split": 2, "stage": 2, "tiles": 4, "aero": 5, "panel": 8}

var renderComponentProperties = map[string]int{"tap": 2, "weight": 2, "current": 8, "nav": 9, "subtitle": 10, "primary": 10, "secondary": 10, "back": 10}

var renderTemplates = map[string]int{"glance": 1, "tile": 4}

var renderPageRoles = map[string]int{"launcher": 9, "onboarding": 10}

var renderPermissions = map[string]int{"system.update": 10, "system.plugins": 10}

var renderLayoutSlots = map[string]int{"panel.update": 10, "form.hero": 10}

var renderFormFields = map[string]int{"link": 1, "confirm": 9, "preview": 9, "nav": 9, "link.icon": 10, "nav.items.icon": 10, "time": 10}

var renderSettingTypes = map[string]int{"time": 10}

var renderFormIcons = map[string]bool{"wifi": true, "wifi-off": true, "volume": true, "volume-off": true, "brightness": true, "tv": true, "radio": true, "cast": true, "apps": true, "settings": true, "clear-day": true, "clear-night": true, "partly-day": true, "partly-night": true, "cloud": true, "fog": true, "rain": true, "shower": true, "snow": true, "thunder": true, "sun": true, "moon": true, "unknown": true, "monitor": true, "map-pin": true, "house": true, "sliders-horizontal": true, "puzzle": true, "moon-star": true, "sunset": true, "bluetooth": true}

// ValidateSettingTime accepts only API10 HH:mm values in five-minute steps.
func ValidateSettingTime(value string, api int) error {
    if api < renderSettingTypes["time"] || api > PluginAPI { return fmt.Errorf("time requires a newer plugin API") }
    if len(value) != 5 || value[2] != ':' || value[0] < '0' || value[0] > '2' || value[1] < '0' || value[1] > '9' || value[3] < '0' || value[3] > '5' || (value[4] != '0' && value[4] != '5') || (value[0] == '2' && value[1] > '3') { return fmt.Errorf("time must be HH:mm in five-minute steps") }
    return nil
}

// ValidateFormIcon applies the fixed descriptor vocabulary and API gate, not image paths or style parameters.
func ValidateFormIcon(name string, api int, navigation bool) error {
    key := "link.icon"
    if navigation { key = "nav.items.icon" }
    if api < 1 || api > PluginAPI || api < renderFormFields[key] { return fmt.Errorf("form icon requires a newer plugin API") }
    if !renderFormIcons[name] { return fmt.Errorf("unknown form vector icon") }
    return nil
}

// ValidatePageRoles follows manifest schema validation; actual system grants belong to the device.
func ValidatePageRoles(roles []string, permissions []string, api int) error {
    if api < 1 || api > PluginAPI { return fmt.Errorf("unsupported plugin API %d", api) }
    for _, permission := range permissions {
        if minimum := renderPermissions[permission]; minimum > api { return fmt.Errorf("permission requires a newer plugin API") }
    }
    for _, role := range roles {
        if minimum := renderPageRoles[role]; minimum > api { return fmt.Errorf("page role requires a newer plugin API") }
        if role == "launcher" {
            allowed := false
            for _, permission := range permissions { if permission == "system.apps" { allowed = true } }
            if !allowed { return fmt.Errorf("the launcher role needs the system.apps permission") }
        }
        if role == "onboarding" || role == "settings" {
            allowed := false
            for _, permission := range permissions { if permission == "system.settings" { allowed = true } }
            if !allowed { return fmt.Errorf("the settings/onboarding role needs the system.settings permission") }
        }
    }
    return nil
}

// ValidateRenderAPI gates the fixed render vocabulary after schema validation.
// The version table is generated from layouts.json, the same source used by Android and Python.
func ValidateRenderAPI(value any, api int) error {
    if api < 1 || api > PluginAPI { return fmt.Errorf("unsupported plugin API %d", api) }
    var walk func(any) error
    walk = func(value any) error {
        switch node := value.(type) {
        case []any:
            for _, child := range node { if err := walk(child); err != nil { return err } }
        case map[string]any:
            if raw, present := node["layout"]; present {
                name, ok := raw.(string)
                minimum, known := renderLayouts[name]
                if !ok || !known || minimum > api { return fmt.Errorf("layout requires a newer plugin API") }
            }
            if slots, present := node["slots"].(map[string]any); present && api < 6 {
                for _, slot := range slots {
                    if reference, isObject := slot.(map[string]any); isObject {
                        if _, widget := reference["widget"]; widget { return fmt.Errorf("widget references in slots require a newer plugin API") }
                    }
                }
            }
            if raw, present := node["template"]; present {
                name, ok := raw.(string)
                minimum, known := renderTemplates[name]
                if !ok || !known || minimum > api { return fmt.Errorf("template requires a newer plugin API") }
                if _, photo := node["image"]; photo && name == "tile" && api < 6 { return fmt.Errorf("tile image requires a newer plugin API") }
                if faces, present := node["faces"].([]any); present && name == "tile" && api < 6 {
                    for _, face := range faces {
                        if object, isObject := face.(map[string]any); isObject {
                            if _, own := object["content"]; own { return fmt.Errorf("tile face content requires a newer plugin API") }
                        }
                    }
                }
            }
            if slots, present := node["slots"].(map[string]any); present {
                layout, _ := node["layout"].(string)
                for slot := range slots {
                    if minimum := renderLayoutSlots[layout+"."+slot]; minimum > api { return fmt.Errorf("layout slot requires a newer plugin API") }
                }
            }
            rawKind, component := node["type"]
            if component {
                kind, ok := rawKind.(string)
                minimum, known := renderComponentTypes[kind]
                if !ok || !known || minimum > api { return fmt.Errorf("component requires a newer plugin API") }
                for key, minimum := range renderComponentProperties {
                    if _, present := node[key]; present && minimum > api { return fmt.Errorf("component property requires a newer plugin API") }
                }
            }
            _, children := node["children"]
            _, widget := node["template"]
            if children && !widget && !component && api < 2 { return fmt.Errorf("component groups require a newer plugin API") }
            for _, child := range node { if err := walk(child); err != nil { return err } }
        }
        return nil
    }
    return walk(value)
}
