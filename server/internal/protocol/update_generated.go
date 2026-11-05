// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_go.py.
package protocol

type UpdateCachedCatalogDocument struct {
	Schema int `json:"schema"`
	Channel string `json:"channel"`
	Body string `json:"body"`
	Signature string `json:"signature"`
	Source string `json:"source"`
	Etag *string `json:"etag,omitempty"`
}

type UpdateCatalogDocument struct {
	Schema int `json:"schema"`
	Channel string `json:"channel"`
	Seq int64 `json:"seq"`
	Published int64 `json:"published"`
	Expires int64 `json:"expires"`
	Release UpdateRelease `json:"release"`
	Mirrors []string `json:"mirrors,omitempty"`
}

type UpdateRelease struct {
	Version string `json:"version"`
	Code int `json:"code"`
	Api int `json:"api"`
	Device []string `json:"device"`
	Sdk int `json:"sdk"`
	Sha256 string `json:"sha256"`
	Size int64 `json:"size"`
	Signer string `json:"signer"`
	Urls []string `json:"urls"`
	Modules []UpdateModule `json:"modules"`
	Notes map[string]string `json:"notes"`
	Source string `json:"source"`
}

type UpdateModule struct {
	Id string `json:"id"`
	Kind string `json:"kind"`
	Version string `json:"version"`
	Code int `json:"code"`
	Policy string `json:"policy"`
	Sha256 string `json:"sha256"`
	Size int64 `json:"size"`
	Urls []string `json:"urls"`
	ManifestUrls []string `json:"manifest_urls"`
}

type UpdateDebugKeyDocument struct {
	Schema int `json:"schema"`
	Test UpdatePublicKey `json:"test"`
}

type UpdateFailureDocument struct {
	Schema int `json:"schema"`
	Entry UpdateInstallFailure `json:"entry"`
}

type UpdateInstallHistory struct {
	Schema int `json:"schema"`
	Failures []UpdateInstallFailure `json:"failures"`
	Result *UpdateInstallResult `json:"result,omitempty"`
}

type UpdateInstallFailure struct {
	Code int `json:"code"`
	Version string `json:"version"`
	Count int `json:"count"`
	Blocked bool `json:"blocked"`
	LastTransaction string `json:"last_transaction"`
	Reason string `json:"reason"`
}

type UpdateInstallResult struct {
	TransactionId string `json:"transaction_id"`
	Kind string `json:"kind"`
	Code int `json:"code"`
	Version string `json:"version"`
	Recorded UpdateInstallTime `json:"recorded"`
	NoticeMs int64 `json:"notice_ms"`
	Notes map[string]string `json:"notes"`
	Reason *string `json:"reason,omitempty"`
	NeedsRecoveryClear bool `json:"needs_recovery_clear"`
}

type UpdateRepairRecord struct {
	Schema int `json:"schema"`
	Id string `json:"id"`
	State string `json:"state"`
	Current UpdateApkIdentity `json:"current"`
	Pending UpdateRepairFile `json:"pending"`
	Previous *UpdateRepairFile `json:"previous,omitempty"`
	History *UpdateRepairFile `json:"history,omitempty"`
	RootState string `json:"root_state"`
	Recorded UpdateInstallTime `json:"recorded"`
	BoundTransaction *string `json:"bound_transaction,omitempty"`
	PriorMarker *UpdateRepairFile `json:"prior_marker,omitempty"`
}

type UpdateRepairFile struct {
	Size int64 `json:"size"`
	Sha256 string `json:"sha256"`
	Key string `json:"key"`
	Modified string `json:"modified"`
}

type UpdateKeyDocument struct {
	Schema int `json:"schema"`
	Channel UpdateKeyPair `json:"channel"`
	Registry UpdateKeyPair `json:"registry"`
}

type UpdatePublicKey struct {
	Id string `json:"id"`
	Algorithm string `json:"algorithm"`
	Spki string `json:"spki"`
}

type UpdateKeyPair struct {
	Active UpdatePublicKey `json:"active"`
	Backup UpdatePublicKey `json:"backup"`
}

type ModuleUpdatePending struct {
	Schema int `json:"schema"`
	Id string `json:"id"`
	RootId string `json:"root_id"`
	PreviousRootId string `json:"previous_root_id"`
	CatalogDigest string `json:"catalog_digest"`
	Channel string `json:"channel"`
	From UpdateApkIdentity `json:"from"`
	To UpdateApkIdentity `json:"to"`
	Mode string `json:"mode"`
	Phase string `json:"phase"`
	Operation string `json:"operation"`
	OperationId *string `json:"operation_id,omitempty"`
	Selected []ModuleUpdateTarget `json:"selected"`
	Staged []string `json:"staged"`
	Created UpdateInstallTime `json:"created"`
	Attempts int `json:"attempts"`
	MaxAttempts int `json:"max_attempts"`
	HealthMs int64 `json:"health_ms"`
	PollMs int64 `json:"poll_ms"`
	RebootBoot *int64 `json:"reboot_boot,omitempty"`
	TrialId *string `json:"trial_id,omitempty"`
	TrialStarted *UpdateInstallTime `json:"trial_started,omitempty"`
	Reason *string `json:"reason,omitempty"`
	Feature *string `json:"feature,omitempty"`
}

type ModuleUpdateTarget struct {
	Id string `json:"id"`
	Code int `json:"code"`
}

type UpdatePending struct {
	Schema int `json:"schema"`
	TransactionId string `json:"transaction_id"`
	Phase string `json:"phase"`
	Channel string `json:"channel"`
	CatalogDigest string `json:"catalog_digest"`
	Seq int64 `json:"seq"`
	From UpdateApkIdentity `json:"from"`
	To UpdateApkIdentity `json:"to"`
	Mode string `json:"mode"`
	Attempts int `json:"attempts"`
	MaxAttempts int `json:"max_attempts"`
	HealthMs int64 `json:"health_ms"`
	NoticeMs int64 `json:"notice_ms"`
	FailureLimit int `json:"failure_limit"`
	Created UpdateInstallTime `json:"created"`
	Notes map[string]string `json:"notes"`
	SessionId *int `json:"session_id,omitempty"`
	RollbackSessionId *int `json:"rollback_session_id,omitempty"`
	TrialId *string `json:"trial_id,omitempty"`
	TrialStarted *UpdateInstallTime `json:"trial_started,omitempty"`
}

type UpdateApkIdentity struct {
	Version string `json:"version"`
	Code int `json:"code"`
	Sha256 string `json:"sha256"`
	Size int64 `json:"size"`
	Signer string `json:"signer"`
}

type UpdateInstallTime struct {
	Elapsed int64 `json:"elapsed"`
	Boot *int64 `json:"boot,omitempty"`
	Wall *int64 `json:"wall,omitempty"`
}

type UpdateSequenceDocument struct {
	Schema int `json:"schema"`
	Stable *UpdateSequence `json:"stable,omitempty"`
	Beta *UpdateSequence `json:"beta,omitempty"`
}

type UpdateSequence struct {
	Seq int64 `json:"seq"`
	Digest string `json:"digest"`
}

type UpdateStateDocument struct {
	Schema int `json:"schema"`
	Channel string `json:"channel"`
	Phase string `json:"phase"`
	Downloaded int64 `json:"downloaded"`
	Size int64 `json:"size"`
	Seq *int64 `json:"seq,omitempty"`
	CatalogDigest *string `json:"catalog_digest,omitempty"`
	Error *string `json:"error,omitempty"`
	ReadyElapsed *int64 `json:"ready_elapsed,omitempty"`
	ReadyBoot *int64 `json:"ready_boot,omitempty"`
	ReadyWall *int64 `json:"ready_wall,omitempty"`
}

type UpdateStatusDocument struct {
	Schema int `json:"schema"`
	Phase string `json:"phase"`
	Channel string `json:"channel"`
	CurrentVersion string `json:"current_version"`
	CurrentCode int `json:"current_code"`
	Configured bool `json:"configured"`
	CanCheck bool `json:"can_check"`
	CanDownload bool `json:"can_download"`
	CanInstall bool `json:"can_install"`
	Downloaded int64 `json:"downloaded"`
	Size int64 `json:"size"`
	Components []UpdateComponentStatus `json:"components"`
	Version *string `json:"version,omitempty"`
	Code *int `json:"code,omitempty"`
	Notes *string `json:"notes,omitempty"`
	Error *string `json:"error,omitempty"`
	Blocked *string `json:"blocked,omitempty"`
	SilentInstall *bool `json:"silent_install,omitempty"`
	Result *UpdateResultStatus `json:"result,omitempty"`
	Inventory *UpdateSystemInventory `json:"inventory,omitempty"`
}

type UpdateComponentStatus struct {
	Id string `json:"id"`
	Version string `json:"version"`
	Code int `json:"code"`
	State string `json:"state"`
	InstalledCode *int `json:"installed_code,omitempty"`
	Downloaded *int64 `json:"downloaded,omitempty"`
	Size *int64 `json:"size,omitempty"`
	Result *string `json:"result,omitempty"`
}

type UpdateResultStatus struct {
	Kind string `json:"kind"`
	Version string `json:"version"`
	Code int `json:"code"`
	Message string `json:"message"`
	Notes string `json:"notes"`
	Reason *string `json:"reason,omitempty"`
}

type UpdateSystemInventory struct {
	Updater string `json:"updater"`
	Modules []UpdateInstalledComponent `json:"modules"`
}

type UpdateInstalledComponent struct {
	Id string `json:"id"`
	InstalledCode *int `json:"installed_code,omitempty"`
}

type UpdateSystemKeyDocument struct {
	Schema int `json:"schema"`
	Active UpdatePublicKey `json:"active"`
	Backup UpdatePublicKey `json:"backup"`
}

type UpdaterStatusDocument struct {
	Protocol int `json:"protocol"`
	Keys int `json:"keys"`
	Transaction bool `json:"transaction"`
	Id string `json:"id"`
	Phase int `json:"phase"`
	Result int `json:"result"`
	RollbackAttempted bool `json:"rollback_attempted"`
	Modules []UpdaterModuleStatus `json:"modules"`
	Setup *UpdaterSetupCapabilities `json:"setup,omitempty"`
}

type UpdaterModuleStatus struct {
	Id string `json:"id"`
	Code int `json:"code"`
	Floor int `json:"floor"`
	Staged int `json:"staged"`
	Active *bool `json:"active,omitempty"`
}

type UpdaterSetupCapabilities struct {
	Timezone bool `json:"timezone"`
	Language bool `json:"language"`
	DeviceOwner bool `json:"device_owner"`
}
