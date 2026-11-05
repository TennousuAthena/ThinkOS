// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_go.py.
package protocol

type RegistryCache struct {
	Schema int `json:"schema"`
	Document string `json:"document"`
	Signature string `json:"signature"`
}

type RegistryCatalog struct {
	Schema int `json:"schema"`
	Seq int64 `json:"seq"`
	Generated int64 `json:"generated"`
	Expires int64 `json:"expires"`
	BlobBases []string `json:"blob_bases"`
	Revoked []RegistryRevocation `json:"revoked"`
	Plugins []RegistryPlugin `json:"plugins"`
}

type RegistryReasons struct {
	Network map[string]string `json:"network,omitempty"`
	Alert map[string]string `json:"alert,omitempty"`
	Media map[string]string `json:"media,omitempty"`
	Library map[string]string `json:"library,omitempty"`
	Presence map[string]string `json:"presence,omitempty"`
	Server map[string]string `json:"server,omitempty"`
	Location map[string]string `json:"location,omitempty"`
}

type RegistryRelease struct {
	Version string `json:"version"`
	Commit string `json:"commit"`
	Digest string `json:"digest"`
	Api int `json:"api"`
	Size int64 `json:"size"`
	Permissions []string `json:"permissions"`
	Why RegistryReasons `json:"why"`
	Apps []string `json:"apps"`
}

type RegistryPlugin struct {
	Id string `json:"id"`
	Tier string `json:"tier"`
	Name map[string]string `json:"name"`
	Summary map[string]string `json:"summary"`
	Author string `json:"author"`
	Repo string `json:"repo"`
	License string `json:"license"`
	Tags []string `json:"tags"`
	Icon *string `json:"icon,omitempty"`
	Screenshots []string `json:"screenshots,omitempty"`
	Releases []RegistryRelease `json:"releases"`
}

type RegistryRevocation struct {
	Id string `json:"id"`
	Digest string `json:"digest"`
	Reason string `json:"reason"`
}

type RegistryFiles struct {
	Id string `json:"id"`
	Version string `json:"version"`
	Digest string `json:"digest"`
	Files []RegistryBlob `json:"files"`
}

type RegistryBlob struct {
	Path string `json:"path"`
	Sha256 string `json:"sha256"`
	Size int `json:"size"`
}

type RegistryLedger struct {
	Schema int `json:"schema"`
	Seq int64 `json:"seq"`
	Digest string `json:"digest"`
}

type RegistryState struct {
	Schema int `json:"schema"`
	Disabled bool `json:"disabled"`
	Installed []RegistryInstalled `json:"installed"`
	Blocked []RegistryBlocked `json:"blocked"`
}

type RegistryPrevious struct {
	Version string `json:"version"`
	Digest string `json:"digest"`
	Commit string `json:"commit"`
	Repo string `json:"repo"`
	Tier string `json:"tier"`
	Name string `json:"name"`
	Confirmed []string `json:"confirmed"`
}

type RegistryInstalled struct {
	Id string `json:"id"`
	Version string `json:"version"`
	Digest string `json:"digest"`
	Commit string `json:"commit"`
	Repo string `json:"repo"`
	Tier string `json:"tier"`
	Name string `json:"name"`
	Confirmed []string `json:"confirmed"`
	Auto bool `json:"auto"`
	Enabled bool `json:"enabled"`
	Trial bool `json:"trial"`
	Error *string `json:"error,omitempty"`
	Previous *RegistryPrevious `json:"previous,omitempty"`
}

type RegistryBlocked struct {
	Id string `json:"id"`
	Digest string `json:"digest"`
	Reason string `json:"reason"`
}
