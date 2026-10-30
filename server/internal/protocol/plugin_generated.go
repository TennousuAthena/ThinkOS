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
}

type SchemaVideo struct {
	Type string `json:"type"`
	Tap *string `json:"tap,omitempty"`
	Weight *float64 `json:"weight,omitempty"`
	Fit *string `json:"fit,omitempty"`
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
}

type SchemaSettingChoice struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

type SchemaCommand struct {
	Id string `json:"id"`
	Phrases []string `json:"phrases,omitempty"`
	Gesture *string `json:"gesture,omitempty"`
}

const PluginAPI = 3

var renderComponentTypes = map[string]int{"text": 1, "icon": 1, "clock": 1, "form": 1, "progress": 2, "chart": 2, "list": 2, "daylight": 2, "moon": 2, "image": 3, "carousel": 3}

var renderLayouts = map[string]int{"stacks": 1, "focus": 1, "form": 1, "split": 2, "stage": 2}

var renderComponentProperties = map[string]int{"tap": 2, "weight": 2}

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
