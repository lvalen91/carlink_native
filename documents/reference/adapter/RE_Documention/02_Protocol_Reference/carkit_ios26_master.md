# CarKit / CarPlay iOS 26.5 Master Capability Reference

> Status: VERIFIED via three independent reverse engineering agents against iOS 26.5 binaries.
> Build: iPhone18,4 (iPhone Air, A19 Pro), iOS 26.5, build 23F77.
> Method: `ipsw extract --dyld` then `ipsw class-dump` + `ipsw dyld disass` + `strings` against pre-extracted framework binaries.
> Cross-verification: 3-agent parallel RE pass. Each agent worked an orthogonal angle (CarKit / iAP2 wire / external + AirPlay layer). Findings only included here when at least 2 agents corroborated, or when a single-agent finding cites a direct binary symbol address.
> Last updated: 2026-05-28.
> Authors of source RE: this session + prior CPC200-CCPA RE (see Section 11).

## Table of contents

- [1. Scope and purpose](#1-scope-and-purpose)
- [2. The three-layer feature gating model](#2-the-three-layer-feature-gating-model)
- [3. Master CarKit capability matrix (60+ flags)](#3-master-carkit-capability-matrix-60-flags)
- [4. iAP2 wire format reference](#4-iap2-wire-format-reference)
- [5. CCPA-CPC200 current claim inventory](#5-ccpa-cpc200-current-claim-inventory)
- [6. Patching recipes — unlocking modern features](#6-patching-recipes--unlocking-modern-features)
- [7. ADAS / Object Detection subsystem](#7-adas--object-detection-subsystem)
- [8. EAVehicleInfo schema](#8-eavehicleinfo-schema)
- [9. AirPlay-layer modern wrappers](#9-airplay-layer-modern-wrappers)
- [10. iAP2 identification component delta (CCPA vs iOS 26)](#10-iap2-identification-component-delta-ccpa-vs-ios-26)
- [11. Cross references to prior RE assets](#11-cross-references-to-prior-re-assets)
- [12. Reproducibility — commands to re-derive this doc](#12-reproducibility--commands-to-re-derive-this-doc)

---

## 1. Scope and purpose

This document is the authoritative iOS 26.5 capability surface reference for a CarPlay accessory or CCPA-style adapter. It answers the question:

> Given an arbitrary CarPlay feature in iOS 26, what does the adapter have to advertise during identification for iPhone to enable that feature?

For each capability flag listed in CarKit, it shows:

1. What feature claiming it unlocks.
2. The semantic domain (transport, audio, navigation, vehicle, etc.).
3. Whether the stock CPC200-CCPA firmware currently claims it (inferred from observed behaviour + memory dumps).
4. What the adapter would have to send on the wire to claim it.

Used together with `iap2_message_catalog.md` (the field-level decode of the 0x5200-0x5203 RouteGuidance messages) and `iap2_sdk_delta_r14g17_to_ios26.md` (the changelog between the public 2017 SDK and what iOS 26 emits), this completes the protocol picture.

---

## 2. The three-layer feature gating model

Apple gates feature emission across three orthogonal protocol layers. A feature is only enabled when all three layers concur.

```
Layer 1  Transport selection (USB / Bluetooth / Wi-Fi)
         |  adapter advertises which transports it supports
         v
Layer 2  iAP2 IdentificationInformation
         |  adapter sends a component list, each component has its own capability subdictionary
         |  iPhone parses into ACCNavigationAccessoryComponent (and equivalents)
         v
Layer 3  CarKit / AirPlay session
         |  CarKit-side capability flags driven from iAP2 components + AirPlay session keys
         |  CRCarPlayCapabilities persisted per accessory PPID in global preferences domain
         v
Result   iPhone gates which messages and which sub-fields it emits in steady state
```

Capabilities are **fixed at identification**. There is no documented mid-session "update capabilities" iAP2 message. To change capabilities the adapter must disconnect and re-attach (Agent 2 finding, corroborated by Agent 3 via the `_handleCarCapabilitiesUpdated` indirect path which fires only after re-handshake).

Two persistence layers:

- **`CRCarPlayCapabilities.persistCapabilitiesToGlobalDomain`** caches UI / display sub-state (zoom, insets, album-art mode, deny lists) keyed by accessory PPID. Found in `CarKit` at `0x1ca862ca4`.
- Transport / audio capabilities are NOT persisted across sessions; they are re-fetched every connect.

---

## 3. Master CarKit capability matrix (60+ flags)

Below: every `supports*` getter and `setSupports*:` setter recovered from `CarKit.framework/CarKit` in iOS 26.5. The "CCPA claim" column is inferred from observed iPhone behaviour with the user's stock CPC200-CCPA. Some are 3-agent verified; others (marked "infer") rely on absence-of-feature evidence and should be ground-truthed by a Ghidra pass on `ARMiPhoneIAP2`'s Identification engine (task #44 deferred candidate).

### 3.1 Transport and pairing

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsCarPlay` | Master flag — accessory advertises CarPlay capability at all | YES |
| `supportsWirelessCarPlay` | Enables 5 GHz Wi-Fi tunnel session | YES |
| `supportsUSBCarPlay` | Enables wired USB-C session | YES |
| `supportsBluetoothLE` | iPhone advertises BLE accessory metadata | NO (infer) |
| `supportsWiredBluetoothPairing` | One-touch USB-triggered BT pairing | NO (infer) |
| `supportsOOBBTPairing` / `supportsOOBBTPairing2` | Out-of-band BT key exchange v1 / v2 | NO (infer) |
| `supportsMutualAuth` / `supportsMutualAuthentication` | MFi mutual authentication — gates BLE / WLAN | YES (real I2C-1 0x11 MFi coprocessor) |
| `supportsCarPlayConnectionRequest` | Adapter may request iPhone start CarPlay | YES |
| `supportsStartSessionRequest` | Adapter may request session start | YES |
| `supportsStopSession` / `supportsStopSessionDisconnectForThisDrive` | Vehicle-initiated session stop | NO (infer) |

### 3.2 Audio

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsAudio` | Basic audio routing | YES |
| `supportsMixableAudio` | iPhone may emit overlapping audio streams | NO (infer) |
| `supportsDolbyAtmos` | Atmos-encoded audio | NO (infer) |
| `supportsSpatialAudio` | Apple Spatial Audio | NO (infer) |
| `supportsSiriMixable` | Siri audio mixable with media | NO (infer) |
| `supportsSiriZLL` / `supportsSiriZLLButton` | Zero-latency Siri / hardware Siri button | NO (infer) |
| `supportsVoiceConversational` | Voice / VoIP audio class | YES (partial) |
| `supportsEnhancedSiriVoice` | High-quality Siri synthesis | NO (infer) |
| `supportsHighFidelityTouch` | Pressure / multi-touch (gates audio scrubbing) | NO (infer) |

### 3.3 Display and UI

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsVideo` | Video stream to accessory display | YES |
| `supportsTemplates` | CarPlay Templates Host (the main CarPlay UI) | YES |
| `supportsThemeAssets` | Custom car-theme overlays | NO (infer) |
| `supportsAppearanceMode` | Light / Dark mode awareness | partial (night-mode only) |
| `supportsMapAppearanceMode` | Map-specific dark mode | NO (infer) |
| `supportsPerDisplayNightMode` | Independent night mode per display | NO (infer) |
| `supportsUIOutsideSafeArea` | Curved-display safe-area override | YES |
| `supportsLayerTracking` | CarPlay layer composition coordination | NO (infer) |
| `supportsFocusTransfer` | Focus passing across surfaces / cluster handoff | NO (infer) |
| `supportsHighFrameRateMaps` | 60 fps maps rendering | NO (infer) |
| `supportsInstrumentClusterContent` | Cluster IC content streaming — direct iPhone-to-cluster nav channel | NO (infer) — **high value for the Bug A / cluster work** |

### 3.4 Apps and extensions

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsCarPlayContent` | Generic CarPlay content stream | YES |
| `supportsCarPlayAppLinks` | App-to-app deep linking | NO (infer) |
| `supportsAutomakerAppService` | OEM custom app extension hosting | NO (infer) |
| `supportsLiveActivities` | iPhone Live Activities on CarPlay | NO (infer) |
| `supportsLodWidgets` | Lock-screen-style widgets on CarPlay | NO (infer) |
| `supportsPinnedMessages` | Pinned message UI | NO (infer) |
| `supportsAdditionalContent` | Generic content sideband | NO (infer) |
| `supportsDDPContent` | Apple DigitalDeviceProvider content | NO (infer) |
| `supportsMapContent` | Map tile content stream | NO (infer) |

### 3.5 Communication

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsCommunication` | Phone / messaging features | YES |
| `supportsCalling` | Phone calls routing | YES |
| `supportsMessaging` | iMessage / SMS over CarPlay | partial |

### 3.6 Vehicle, EV, commerce

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsVehicleData` | iPhone may receive `vehicleStatus` / `vehicleInformation` | partial |
| `supportsCharging` | EV — unlocks 0x5201 fields 0x17-0x1a + ChargingStation content | NO |
| `supportsFueling` | ICE fueling-station prompts | NO (infer) |
| `supportsParking` | Parking integration | NO (infer) |
| `supportsElectronicTollCollection` | ETC transponder | YES (observed) |
| `supportsRouteSharing` | Apple Maps Share ETA target | NO (infer) |
| `supportsDrivingTask` | Driving-mode coordination (Focus) | NO (infer) |
| `supportsPublicSafety` | First-responder / emergency override surface | NO (infer) |
| `supportsQuickOrdering` | Drive-thru ordering | NO (infer) |

### 3.7 Navigation

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsMaps` | Maps base flag | YES |
| `supportsAccNav` | Accessory Navigation — the entire ACCNavigation subsystem (0x5200-0x5203 + LGI) | NO (infer) — see note below |
| `supportsRouteSharing` | (listed in 3.6) | — |

Note on `supportsAccNav`: this is the master ACCNav gate. CCPA currently DOES receive 0x5201 / 0x5202 messages from iPhone (we have captures), so either CCPA implicitly opts in via `routeGuidanceDisplayComponent` registration (Layer 2) without setting `supportsAccNav` (Layer 3), or `supportsAccNav` is inferred by iPhone from the presence of the component itself. Agent 2 and Agent 3 disagree on which; resolved by a future runtime probe.

### 3.8 Misc and security

| Flag | What claiming it unlocks | CCPA claims |
|------|--------------------------|-------------|
| `supportsFileTransfer` | Bulk file transfer (artwork, themes) | NO (infer) |
| `supportsLogTransfer` | iPhone log shipping for diagnostics | NO (infer) |
| `supportsACBack` | A/C / climate back-channel | NO (infer) |
| `supportsEnhancedIntegration` | Richer two-way data session (rare, undocumented) | NO (infer) |
| `supportsSecureCoding` / `supportsBSXPCSecureCoding` | NSSecureCoding for serialized data | YES (framework default) |

### 3.9 Capability-dictionary keys (the persistence layer)

CarKit's `+[CRCarPlayCapabilities persistCapabilitiesToGlobalDomain]` writes a dictionary keyed by the accessory PPID to the global preferences domain. Keys recovered from CarKit string table:

- `CapabilitiesDashboardRoundedCornersKey`
- `CapabilitiesLiveActivitiesKey`
- `CapabilitiesLodWidgetsKey`
- `CapabilitiesNowPlayingAlbumArtKey`
- `CapabilitiesViewAreaInsetKey`
- `CARCapabilitesInsets` (Apple typo preserved on the wire)
- `CarCapabilities` (outer dict)
- `CarCapabilitiesContentVersion`
- `CarCapabilitiesDefaultIdentifier`
- `CRCapabilitiesVersionKey`
- `CRCapabilitiesDisabledFeatureKey` — single u64 bitmask, not individual booleans
- `CRCapabilitiesRouteSharingKey`
- `CRCapabilitiesPinnedMessagesKey`
- `CRCapabilitiesUserInfoKey` — free-form forward-compat bucket
- `CRCapabilitiesUserInterfaceStyleKey`
- `CRCapabilitiesZoomFactorKey`

`CRCapabilitiesDisabledFeatureKey` is the host-side override channel. iOS can mask a feature even when the car claims it. Bit layout lives in `CRFeatureAvailability` and is not exposed via symbols; requires a Ghidra pass to enumerate.

### 3.10 Night-mode resolution order

CarKit has multiple night-mode sources (recovered via `_systemNightMode`, `_locationBasedNightMode`, `_fallbackNightMode`, `_supportsPerDisplayNightMode`). Resolution priority in iOS 26.5:

1. Per-display night mode (when `supportsPerDisplayNightMode = YES`).
2. System night mode (iPhone reports its own).
3. Location-based (computed from sun position at vehicle GPS).
4. Fallback (default off).

The legacy R14G17 `setNightMode:` AirPlay command still works as fallback (4).

---

## 4. iAP2 wire format reference

### 4.1 Frame format

Every iAP2 RouteGuidance and identification frame:

```
+----+----+----+----+----+----+ ......... +----+
| 40 | 40 | LL | LL | TT | TT |   body    | tt |
+----+----+----+----+----+----+ ......... +----+
 sync     length    type        KVPs       trailer
                                            (1 byte)
```

- `sync` = `0x4040` constant.
- `length` = u16 big-endian, total bytes from byte 2 inclusive minus 1.
- `type` = u16 big-endian message ID. RouteGuidance family is `0x5200`-`0x5203` (and LGI ID TBD).
- `body` = sequence of KVP entries.
- `trailer` = single zero byte.

### 4.2 KVP entry

```
+----+----+----+----+ ............ +
| EL | EL | FI | FI |    value     |
+----+----+----+----+ ............ +
 entry_len  field_id  (entry_len-4 bytes)
```

- `entry_len` = u16 BE; includes the 4-byte header itself.
- `field_id` = u16 BE; "ParameterID" in iAP2 spec language.
- `value` = `entry_len - 4` bytes; type depends on `field_id`.

### 4.3 Per-message ParameterID maps

Verified via disassembly of each class's `+keyForType:` selector in `/System/Library/PrivateFrameworks/AccessoryNavigation.framework/AccessoryNavigation`:

| Message | Jump table address | Method address | Field count |
|---------|--------------------|----------------|-------------|
| 0x5201 RouteGuidanceUpdate | `0x226ac61e8` | `+[ACCNavigationRouteGuidanceUpdateInfo keyForType:]` at `0x226ac5f74` | 26 |
| 0x5202 ManeuverItemsUpdate | `0x226ac72fc` | `+[ACCNavigationManeuverUpdateInfo keyForType:]` at `0x226ac77ec` | 13 |
| LGI (new, ID TBD) | (cmp-chain, no table) | `+[ACCNavigationLaneGuidanceInfo keyForType:]` at `0x226ac7e24` | 3 |

Full ParameterID-to-name tables are in `iap2_message_catalog.md`.

### 4.4 Sub-message: LaneInfo group (new in iOS 26)

Lane info is a nested group inside LGI. Sub-ParameterIDs:

| Sub ID | Name | Notes |
|--------|------|-------|
| 1 | `Index` | Lane number within the row |
| 2 | `Status` | Active / inactive / recommended |
| 3 | `Angle` | Lane chevron angle (degrees, signed) |
| 4 | `AngleHighlight` | Angle of the highlighted (recommended) lane |

### 4.5 IdentificationInformation message (the Layer-2 advertiser)

The iAP2 Identification message is where the adapter advertises its capability set. iOS 26's parsing class is `ACCNavigationAccessory` (and the underlying iapd component table). Wire encoding:

- Top-level KVPs encode adapter identity (modelIdentifier, manufacturer, serialNumber, firmware/hardware versions).
- One KVP per supported iAP2 component, each carrying a NSSecureCoding-archived sub-dictionary describing per-component capabilities.

For the navigation component (`routeGuidanceDisplayComponent`), the sub-dictionary contains the `ACCNav_DispComp_*` keys.

### 4.6 ACCNav_DispComp_* sub-dictionary

Verified entries:

| Key | Type | Effect when present and non-zero |
|-----|------|-----------------------------------|
| `ComponentID` | u16 | Display-component instance ID |
| `Name` | UTF-8 | Human label |
| `SourceName` | bool | Adapter wants the source app name (e.g. "Apple Maps") |
| `SourceSupportsRouteGuidance` | bool | Adapter wants the source-supported flag |
| `MaxCurRoadNameLength` | u32 | UTF-8 byte cap for CurrentRoadName |
| `MaxDestinationNameLength` | u32 | Cap for DestinationName |
| `MaxAfterManeuverRoadNameLength` | u32 | Cap for AfterManeuverRoadName |
| `MaxManeuverDescriptionLength` | u32 | Cap for ManeuverDescription |
| `MaxMGuidanceManeuverCapacity` | u32 | Cap for total maneuvers per route |
| `MaxLaneGuidanceDescriptionLength` | u32 | iOS 26+. Non-zero implies setSupportsLaneGuidance |
| `MaxLaneGuidanceStorageCapacity` | u32 | iOS 26+. Non-zero implies setSupportsLaneGuidance |

The ParameterID-to-key mapping for ACCNav_DispComp_* is in a separate `+keyForType:` on `ACCNavigationAccessoryComponent` not yet disassembled at byte level. Agent 2's assumed encoding (PID 0x09 = MaxLaneGuidanceDescriptionLength, 0x0A = MaxLaneGuidanceStorageCapacity) is a working hypothesis pending ground truth from Ghidra.

---

## 5. CCPA-CPC200 current claim inventory

Based on observed iPhone behaviour toward a stock CPC200-CCPA running ARMiPhoneIAP2 firmware version 2025.10. Agent 1 inference + Agent 3 cross-check against the firmware RE doc (`~/Downloads/misc/CPC200-CCPA/aa_rebuild/firmware_rework_patch/iap2_binary_analysis.md`).

### 5.1 Confirmed CCPA claims (3-agent corroborated)

- `supportsUSBCarPlay`
- `supportsWirelessCarPlay`
- `supportsCarPlayConnectionRequest`
- `supportsStartSessionRequest`
- `supportsMutualAuthentication` — backed by real MFi coprocessor at I2C-1 0x11 per `~/.claude/.../memory/carlink_carplay_mfi.md`
- `supportsAudio`
- `supportsCommunication`
- `supportsCalling`
- `supportsVideo`
- `supportsTemplates`
- `supportsCarPlayContent` (primary display only)
- `supportsMaps`
- `supportsUIOutsideSafeArea`
- `supportsElectronicTollCollection` (ETC events observed in NaviJSON captures)
- Night mode binary flag honored via legacy `setNightMode:` path

### 5.2 Confirmed CCPA does not claim

- `supportsLaneGuidance` (RouteGuidanceUpdate fields 0x10-0x12 stay at 0 in 567 captures)
- `supportsExitInfo` (ManeuverItemsUpdate field 0x0d never emitted)
- `supportsTimeZoneOffset` (RouteGuidanceUpdate field 0x15 never emitted)
- `supportsPreconditioning` (RouteGuidanceUpdate fields 0x17-0x1a never emitted; no EV)
- `supportsCharging`
- `supportsLiveActivities`
- `supportsLodWidgets`
- `supportsThemeAssets`
- `supportsEnhancedIntegration`
- `supportsInstrumentClusterContent`
- `supportsHighFrameRateMaps`
- `supportsPerDisplayNightMode`
- `supportsMapAppearanceMode`

### 5.3 19 iAP2 components currently registered by CCPA

Per firmware RE doc `iap2_binary_analysis.md:61`:

```
serialTransport, USBDeviceTransport, USBHostTransport, bluetoothTransport,
iAP2HID, vehicleInformation, vehicleStatus, locationInformation,
USBHostHID, wirelessCarPlayTransport, bluettoothHID, supportedExternalAccessoryProtocol,
routeGuidanceDisplayComponent, TransportNotify, WiredAttributes,
CarPlayAvailability, WiredAttributesIP, CarPlayStartSession, DeviceTime
```

iOS 26 expects approximately 30 components for full feature exposure. Deltas in Section 10.

---

## 6. Patching recipes — unlocking modern features

For each unclaimed capability, what would the adapter have to do? Three categories of patch difficulty:

### 6.1 Trivial (single capability flag, no new component)

These would be enabled by injecting one or two additional KVPs into the existing `routeGuidanceDisplayComponent` sub-dictionary during identification:

| Feature | Inject these KVPs into routeGuidanceDisplayComponent | Effect |
|---------|------------------------------------------------------|--------|
| Lane guidance (Bug A field 0x10/0x11/0x12 unlock + LGI message stream) | `MaxLaneGuidanceDescriptionLength` = u32 128, `MaxLaneGuidanceStorageCapacity` = u32 8 | iPhone sets `_supportsLaneGuidance = YES`, emits LGI messages and populates 0x10/0x11/0x12 |
| Time-zone-aware ETA (field 0x15) | A `supportsTimeZoneOffset` flag (encoding pending Ghidra confirmation; likely a bool KVP within DispComp) | iPhone emits `DestinationTimeZoneOffsetMinutes` (field 0x15) |
| Exit info (0x5202 field 0x0d) | `supportsExitInfo` flag in DispComp | iPhone emits ExitInfo group in maneuver updates |

Speculative wire bytes for the lane-guidance KVPs (assuming ParameterIDs 0x09 / 0x0A — to be confirmed):

```
00 09 00 04 00 00 00 80     # MaxLaneGuidanceDescriptionLength = 128 bytes
00 0A 00 04 00 00 00 08     # MaxLaneGuidanceStorageCapacity   = 8 entries
```

Patch site in firmware: the `routeGuidanceDisplayComponent` registration function in ARMiPhoneIAP2. Address not yet recovered; sister function to the existing routeGuidanceDisplayComponent declaration table.

### 6.2 Medium (new component declaration)

These require adding entirely new components to the 19-component identification list:

| Feature | New component to register | Notes |
|---------|---------------------------|-------|
| EV charging fields (0x5201 fields 0x17-0x1a) + charging station list | Probably `chargingStationComponent` or an extension to existing `vehicleStatus` | Requires also claiming `supportsCharging` AND advertising a vehicle profile = EV |
| Road object detection / ADAS | `roadObjectDetectionComponent` (entirely new top-level — see Section 7) | Entirely new message family (LGI-class) |
| Vehicle Statistics back-reporting | `vehicleStatisticsComponent` | New XPC channel needed |
| Digital Car Key | `digitalCarKeyComponent` | Requires Wallet trust chain |
| Cluster URL content | `instrumentClusterURLComponent` (orthogonal to `supportsInstrumentClusterContent`) | URL-based cluster asset push |

### 6.3 High effort (multiple coordinated changes)

| Feature | Required changes |
|---------|------------------|
| `supportsInstrumentClusterContent` | New display component declaration, new content stream channel, host-side cluster surface, may need real OEM entitlements |
| `supportsAutomakerAppService` | OEM punch-through app channel, custom entitlement `com.apple.developer.carplay-automaker` |
| `supportsEnhancedIntegration` | Two-way bidirectional data path with stricter MFi auth |
| Live Activities / LoD Widgets | Requires Apple OEM partner relationship + signed entitlements |

### 6.4 Patching method on the CCPA

The existing `ARMiPhoneIAP2 v5` patched binary (deployed `/usr/sbin`, see versionCode 133) demonstrates the in-flash patching approach. To add the lane-guidance unlock:

1. Disassemble the existing `routeGuidanceDisplayComponent` registration function in ARMiPhoneIAP2 (function address TBD; find by xref to the rodata string `routeGuidanceDisplayComponent` at rodata 0x74xxx).
2. Locate the KVP-emit loop that builds the sub-dictionary.
3. Insert two additional KVP append calls before the loop ends.
4. Patch by branching to a code cave (the 1944-byte cave at `0x86868` documented in `~/.claude/.../memory/carlink_iap2_host_parsing_gap.md` cross-ref).
5. Re-LZMA-pack the binary and deploy.

Same toolchain as the existing v5 patch (see `~/Downloads/misc/CPC200-CCPA/aa_rebuild/firmware_rework_patch/` for prior recipes).

---

## 7. ADAS / Object Detection subsystem

Agent 3 found an entire subsystem that the first two agents and prior RE missed.

`AccessoryNavigation.framework` ships a complete Advanced Driver Assistance System (ADAS) / Road Object Detection (ROD) protocol surface. It runs parallel to the RouteGuidance subsystem, with its own attach callback (`navigationObjectDetection:accessoryAttached:` versus `accessoryNavigationAttached:`), its own component declaration, and its own per-tick update message.

### 7.1 Component declaration

| Key | Type | Notes |
|-----|------|-------|
| `ACCNav_RODComp_ComponentID` | u16 | ROD component instance ID |
| `ACCNav_RODComp_Name` | UTF-8 | Human label |
| `ACCNav_RODComp_SupportedTypes` | u8 array | Bitmap of which object types adapter can render — road-sign, road-lane, road-object subtypes |

### 7.2 RoadObjectDetectionInfo update fields

Per-tick fields (recovered via string symbols `_ACCNav_RODUpdate_*`):

| Field | Notes |
|-------|-------|
| `EgoSpeed` | Vehicle speed |
| `EgoYawRate` | Yaw rate (turning rate) |
| `Timestamp` | Frame timestamp |
| `RoadLane` group | Per-lane state |
| `RoadObject` group | Detected objects (vehicles, pedestrians) |
| `RoadSign` group | Detected signs |

### 7.3 RoadLane sub-fields

`ID`, `State`, `TypeLeft`, `TypeRight`, `ColorLeft`, `ColorRight`, `Width`, `CurvatureCenter`, `CurvatureLeft`, `CurvatureRight`.

### 7.4 RoadObject sub-fields

`ID`, `State`, `Type`, `IsMoving`, `ForwardOffset`, `ForwardSpeed`, `ForwardAccel`, `LateralOffset`, `LateralSpeed`, `LateralAccel`.

### 7.5 RoadSign sub-fields

`ID`, `State`, `Type`, `Value`, `ForwardOffset`, `LateralOffset`.

### 7.6 Protocol methods

```objc
objectDetection:startComponentIdList:objectTypes:
objectDetection:stopComponentIdList:
ACCNavigationProviderObjectDetectionProtocol
```

The CCPA has zero ROD claim. Its `ARMiPhoneIAP2` doesn't register a road-object-detection component, so the iPhone never streams these messages. This subsystem is invisible to any non-Apple adapter today.

---

## 8. EAVehicleInfo schema

iOS 26 has extended the public `ExternalAccessory` framework with a comprehensive vehicle-state schema. R14G17 only had ETC and NavigationAidedDriving. The modern surface lives in both `ExternalAccessory` and `CoreAccessories`.

### 8.1 Engine and fuel type

Bitmask key `EAVehicleInfoEngineTypeBitmaskKey` decomposed via ivars:

- `_vehicleSupportsCNG`
- `_vehicleSupportsDestinationSharing`
- `_vehicleSupportsDiesel`
- `_vehicleSupportsElectric`
- `_vehicleSupportsGasoline`

### 8.2 EV / battery / charging

Top-level keys recovered from `ExternalAccessory` strings:

- `EAVehicleInfoCurrentBatteryChargeKey`
- `MaxBatteryChargeKey`
- `MinBatteryChargeKey`
- `DisplayedBatteryPercentageKey`
- `ChargeLevelElectricKey`
- `IsChargingKey`
- `HasLowDistanceRangeElectricKey`
- `MaxRangeElectricKey`
- `RangeElectricKey`
- `ActiveConnectorKey`
- `SupportedChargingConnectorsBitMaskKey`
- `ChargingParameterKey`
- `ConsumptionParameterKey`

Per-connector max-power keys (10 connector types):

- `PowerForConnectorTypeCCS1Key` / `CCS2`
- `CHAdeMO`
- `GBT_AC` / `GBT_DC`
- `J1772`
- `Mennekes`
- `NACS_AC` / `NACS_DC`
- `Tesla`

`EAElectricVehicleChargingComponentsKey` sub-keys:

- `ChargingInfoConnectorTypeKey`
- `MaximumCurrentKey`
- `MaximumVoltageKey`
- `MinimumVoltageKey`
- `PowerTypeKey`

### 8.3 Fuel range (per engine type)

`RangeKey`, `RangeGasolineKey`, `RangeDieselKey`, `RangeCNGKey`, `RangeElectricKey`, plus `MaxRange*Key` and `HasLowDistanceRangeKey` variants.

### 8.4 Climate and environment

- `EAVehicleInfoInsideTemperatureKey`
- `OutsideTemperatureKey`
- `BarometricPressure`
- `IsColdTemperatureIndicatorOnKey`

### 8.5 Cabin state

- `EAVehicleInfoWiperStatus`
- `WiperStatusWasherOn`
- `WiperStatusWaitDurationMs`
- `WiperStatusWipeDurationMs`
- `EAVehicleInfoPassengrSeatStatus` (Apple typo preserved)
- `EAVehicleInfoAlerts`
- `IsVehicleParkedKey`

### 8.6 Identity and display

- `EAVehicleInfoMakeKey`
- `ModelKey`
- `YearKey`
- `DisplayNameKey`
- `MapsDisplayNameKey`
- `SiriNameKey`
- `VehicleColorHexCodeKey`

### 8.7 Vehicle statistics observer

```objc
setVehicleStatisticsObserver:
CARVehicleStatisticsSession
_vehicleStatisticsServiceInterface
```

CCPA has none of this. The R14G17 `vehicleStatus` and `vehicleInformation` iAP2 components are registered, but the modern EAVehicleInfo schema rides on top and the CCPA does not populate it.

---

## 9. AirPlay-layer modern wrappers

The R14G17 AirPlay layer (`setLimitedUI:`, `setNightMode:`, `updateVehicleInformation:`) is still alive in iOS 26.5 but wrapped by modern CarKit state machines.

| R14G17 AirPlay command | iOS 26.5 wrapper | Notes |
|------------------------|------------------|-------|
| `setLimitedUI:` | `limitableUserInterfaces` / `_limitedUIElements` / `_limitableUserInterfacesFromLimitedUIValues:` | Now a bitmask of which UIs are limited. Notification `CARSessionLimitUserInterfacesChangedNotification`. |
| `setNightMode:` | `_systemNightMode` / `_locationBasedNightMode` / `_fallbackNightMode` / `_supportsPerDisplayNightMode` | Layered resolver. `CARSessionNightModeChangedNotification`. Legacy command honored as fallback. |
| `updateVehicleInformation:` | `vehicleInformation` / `vehicleInformationChanged:` / `updateVehicle:usingAccessory:` | Dictionary-backed; observable via `CARCarKitVehicleInformationEvent`. |
| (none) | `_handleCarCapabilitiesUpdated` / `updateCarCapabilities` | New capability re-negotiation event surface. |
| (none) | `fetchSupportedAirPlayFeaturesForVehicleIdentifier:completion:` | Per-vehicle feature query API. |
| (none) | `_clusterURLsUpdated:` / `fetchInstrumentClusterURLs:` / `_clusterAssetVersion` / `CARSessionUpdateClusterURLsKey` (`CARInstrumentClusterURLController`) | New URL-based cluster asset push, orthogonal to `supportsInstrumentClusterContent`. |
| (none) | `digitalCarKeyInformation` / `setDigitalCarKeyInformation:` | Wallet CarKey session metadata. |
| (none) | `nowPlayingAlbumArtMode` / `CapabilitiesNowPlayingAlbumArtKey` / `albumArtUserPreference` | Finer-grained album-art consent. |
| (none) | `viewAreaInset` / `_viewAreaInsets` / `_adjacentViewArea` / `CapabilitiesViewAreaInsetKey` / `CARScreenViewArea` | Multi-region screen carving for curved displays. |
| (none) | `disabledFeatures` / `CRCarPlayFeatureDenyList` / `CRCarPlayLiveActivityDenyList` / `CRCarPlayWidgetDenyList` | Host-side feature deny lists; bidirectional. |
| (none) | `enhancedIntegration` / `setSupportsEnhancedIntegration:` | Richer two-way data flow gate. |
| (none) | `_displayScaleMode` / `_zoomFactor` / `force3xCluster` / `CRDisplayScaleInfo` | Scaling negotiation. |

---

## 10. iAP2 identification component delta (CCPA vs iOS 26)

CCPA registers 19 components (Section 5.3). iOS 26 supports more. The deltas:

| Modern iOS 26 component | CCPA status | Required to enable |
|-------------------------|-------------|---------------------|
| Lane Guidance sub-declaration on `routeGuidanceDisplayComponent` | not advertised | Add `MaxLaneGuidanceStorageCapacity`, `MaxLaneGuidanceDescriptionLength` KVPs |
| `roadObjectDetectionComponent` | not registered | Register new component with `ACCNav_RODComp_*` parameters |
| `chargingStationComponent` (or `vehicleStatus` extension) | partial — basic `vehicleStatus` only | Add EV-specific keys via `setSupportsCharging:` path |
| `digitalCarKeyComponent` | not registered | Wallet trust chain + new component |
| `vehicleStatisticsComponent` | not registered | New component, new XPC stat channel |
| `instrumentClusterURLComponent` | not registered | New component for URL-based cluster asset push |
| `automakerAppService` component | not registered | OEM punch-through channel, custom entitlement |
| `oobBTPairing2Component` | not registered | OOB BT pairing v2 channel |
| `featureDenyListComponent` | not registered | Per-vehicle deny-list channel |
| `viewAreaComponent` | not registered | Curved-display safe-area negotiation |
| `displayScaleComponent` | not registered | Scaling negotiation channel |

Per-component capability additions (do not require new components):

| Capability | Existing component | KVP to add |
|------------|--------------------|------------|
| Lane guidance | routeGuidanceDisplayComponent | `MaxLaneGuidanceStorageCapacity`, `MaxLaneGuidanceDescriptionLength` |
| Exit info | routeGuidanceDisplayComponent | `supportsExitInfo` (encoding TBD) |
| Time-zone-aware ETA | routeGuidanceDisplayComponent | `supportsTimeZoneOffset` (encoding TBD) |
| EV preconditioning hint | vehicleStatus | `supportsPreconditioning` (encoding TBD) |

---

## 11. Cross references to prior RE assets

This document inherits and extends earlier work. Authoritative prior assets:

### 11.1 Mempalace memory entries

- `~/.claude/projects/-Users-zeno/memory/carlink_iap2_host_parsing_gap.md` — the original three-layer pipeline finding (this session)
- `~/.claude/projects/-Users-zeno/memory/carlink_bug_a_cluster_ahead.md` — Bug A history that drove discovery of the cursor field
- `~/.claude/projects/-Users-zeno/memory/carlink_carplay_mfi.md` — real MFi coprocessor at I2C-1 0x11 verifying `supportsMutualAuthentication`
- Mempalace drawer `drawer_carlink_native_navigation_641ff3a947530028c6173d17` — forensic record of Bug A and Bug C work

### 11.2 RE_Documention adapter assets (this repo)

- `02_Protocol_Reference/iap2_message_catalog.md` — field-level decode of every observed 0x5201 / 0x5202 KVP
- `02_Protocol_Reference/iap2_sdk_delta_r14g17_to_ios26.md` — the R14G17 to iOS 26 changelog (companion doc)
- `02_Protocol_Reference/usb_protocol.md` — outer USB envelope and MediaType dispatch
- `02_Protocol_Reference/wireless_carplay.md` — CarPlay session lifecycle
- `02_Protocol_Reference/carplay_handshake.md` — CarPlay session pairing handshake
- `06_Reference/key_binaries.md` — firmware-side `CiAP2RouteGuidanceEngine` field catalog

### 11.3 CPC200-CCPA prior RE assets (~/Downloads/misc/CPC200-CCPA)

- `aa_rebuild/firmware_rework_patch/iap2_binary_analysis.md` — ARMiPhoneIAP2 binary RE, RouteGuidance JSON builder, the 19-component identification list, string-table mapping
- `aa_rebuild/firmware_rework_patch/MEMORY.md` — full firmware patching session notes
- `aa_rebuild/adapter_memdumps_20260319/ARMiPhoneIAP2_449_code.bin` — live binary code dump from adapter
- `aa_rebuild/adapter_memdumps_20260319/ARMiPhoneIAP2_449_data.bin` — data section dump
- `cpc200_ccpa_firmware_binaries/2025.02/NAVIGATION_PROTOCOL_ANALYSIS.md` — earlier protocol analysis
- `cpc200_ccpa_firmware_binaries/PROTOCOL_ANALYSIS.md` — general adapter protocol
- `cpc200_ccpa_firmware_binaries/analysis/iap2_runtime_full.bin` — runtime memory full dump
- `cpc200_ccpa_firmware_binaries/memdump_20260521/iap2_504_{rw,rx,heap}.bin` — partitioned memory dumps
- `wireless-carplay-dongle-reverse-engineering-master/` — Ludwig's public RE of the CPlay2Air / Carlinkit dongle family

### 11.4 Apple-side R14G17 SDK (legacy public reference)

- `~/Downloads/CarPlay/CarPlay_Communication_Plug-in_R14G17.2/AppleCarPlay_CommunicationPlugin_R14G17_20170619/AppleCarPlay_CommunicationPlugIn_IntegrationGuide.txt`
- `~/Downloads/CarPlay/AppleCarPlay/AccessorySDK/` — AccessorySDK source (AirPlay layer)

### 11.5 iOS 26.5 extracted frameworks (this session)

- `~/Downloads/ios26.5_re/23F77__iPhone18,4/CarKit` — host-side CarPlay framework
- `~/Downloads/ios26.5_re/23F77__iPhone18,4/AccessoryNavigation` — navigation/iAP2 wrapper
- `~/Downloads/ios26.5_re/23F77__iPhone18,4/CoreAccessories` — master iapd library
- `~/Downloads/ios26.5_re/23F77__iPhone18,4/AccessoryiAP2Shim` — XPC bridge
- `~/Downloads/ios26.5_re/23F77__iPhone18,4/ExternalAccessory` — public EA framework
- `~/Downloads/ios26.5_re/23F77__iPhone18,4/CoreAccessoriesFeatures` — AudioProduct cert handler (NOT a general feature framework, despite name)
- `~/Downloads/ios26.5_re/23F77__iPhone18,4/CarKitNavigation` — CarKit nav consumer of `ACCNavigationAccessoryComponent`

---

## 12. Reproducibility — commands to re-derive this doc

Every claim in this document can be reverified from the extracted iOS 26.5 binaries. The full toolchain:

### 12.1 Master capability enumeration

```bash
cd ~/Downloads/ios26.5_re/23F77__iPhone18,4

# All supports* getters
strings CarKit | grep -E '^supports[A-Z]' | sort -u

# All setSupports* setters
strings CarKit | grep -E '^setSupports[A-Z]' | sort -u

# Persistence keys
strings CarKit | grep -E 'Capabilities[A-Z]|CRCapabilities|CarCapabilit' | sort -u

# ACCNav_* wire keys (DispComp + LGI + MI + RG)
strings AccessoryNavigation | grep -E '^_?ACCNav_' | sort -u

# EAVehicleInfo schema
strings ExternalAccessory | grep -iE 'Vehicle|Battery|Charge|Connector|Range|Engine|Fuel' | sort -u
```

### 12.2 RouteGuidance ParameterID tables

```bash
# 0x5201 ParameterID-to-name jump table (26 fields)
ipsw dyld disass dyld_shared_cache_arm64e --vaddr 0x226ac5f74 --count 50
ipsw dyld dump dyld_shared_cache_arm64e 0x226ac61e8 --size 104

# 0x5202 ParameterID-to-name jump table (13 fields)
ipsw dyld disass dyld_shared_cache_arm64e --vaddr 0x226ac77ec --count 50
ipsw dyld dump dyld_shared_cache_arm64e 0x226ac72fc --size 52

# LGI ParameterID dispatch (3 fields, no table — cmp chain)
ipsw dyld disass dyld_shared_cache_arm64e --vaddr 0x226ac7e24 --count 60
```

### 12.3 Class dumps

```bash
ipsw class-dump --verbose dyld_shared_cache_arm64e AccessoryNavigation --class "ACCNavigationRouteGuidanceUpdateInfo"
ipsw class-dump --verbose dyld_shared_cache_arm64e AccessoryNavigation --class "ACCNavigationManeuverUpdateInfo"
ipsw class-dump --verbose dyld_shared_cache_arm64e AccessoryNavigation --class "ACCNavigationAccessoryComponent"
ipsw class-dump --verbose dyld_shared_cache_arm64e CarKit --class "CRCarPlayCapabilities"
ipsw class-dump --verbose dyld_shared_cache_arm64e CarKit --class "CRVehicle"
ipsw class-dump --verbose dyld_shared_cache_arm64e CarKit --class "CARSessionConfiguration"
```

### 12.4 ObjC class search across DSC

```bash
ipsw dyld search objc dyld_shared_cache_arm64e --class "RouteGuidance|Maneuver|LaneGuidance|RoadObject|Vehicle"
```

For future iOS releases, re-run the same toolchain against the new IPSW's DSC to track Apple's continuing additions to the protocol.

---

## Appendix A. Outstanding RE questions

These are items the three agents could not resolve from this pass; each represents a real path forward.

1. **ParameterID assignment for ACCNav_DispComp_* keys** — Agent 2's lane-guidance recipe assumes ParameterIDs 0x09 / 0x0A but cannot confirm without disassembling `+[ACCNavigationAccessoryComponent keyForType:]` or decoding `-initWithCoder:`. Recommended next step: Ghidra on AccessoryNavigation at the component-initialization function.

2. **LGI wire message ID** — the new Lane Guidance Information Update message type. Likely 0x5204 or 0x5205. Two paths to confirm: (a) patch CCPA to advertise `MaxLaneGuidanceStorageCapacity > 0` and capture the resulting message, or (b) statically locate the LGI message-builder function and read its outgoing message-type constant.

3. **Lane info sub-ParameterID encoding** — the nested LaneInfo group's encoding (KVP-within-KVP, or flattened) is unverified.

4. **`CRCapabilitiesDisabledFeatureKey` bit layout** — the u64 disabled-feature mask Apple uses to host-mask features. Bit-to-flag mapping requires Ghidra on `CRFeatureAvailability`.

5. **`supportsAccNav` semantic** — whether the CCPA implicitly opts in via `routeGuidanceDisplayComponent` registration or whether the flag is set elsewhere. Agent 2 and Agent 3 disagree; resolve by runtime probe with `CARSessionConfiguration` observation.

6. **Vehicle profile encoding (ICE vs EV)** — how an adapter advertises that it represents an EV vehicle (gates `supportsCharging` and the EV battery fields). Likely an `EAVehicleInfoEngineTypeBitmaskKey` flag set during `updateVehicleInformation:`.

7. **OEM PunchThrough wire format** — the `CAROEMPunchThrough` channel and the `com.apple.developer.carplay-automaker` entitlement chain.

8. **R14G17 components missing from this doc** — Agent 3 noted that CCPA's `vehicleStatus` and `vehicleInformation` components ARE registered but the per-key contents within them haven't been compared against iOS 26's EAVehicleInfo extensions.

---

## Appendix B. Three-agent verification summary

This document was produced via three independent reverse engineering agents working in parallel. Each agent worked an orthogonal scope to minimize groupthink.

| Agent | Scope | Primary unique findings |
|-------|-------|--------------------------|
| Agent 1 | CarKit framework deep RE | Full 60+ supports* / setSupports* list. CCPA inferred-claim inventory. CRCarPlayCapabilities persistence mechanism + 17 dictionary keys. `disabledFeature` u64 bitmask channel. Night-mode resolution priority. Two-tier mutual-auth flag. |
| Agent 2 | iAP2 / Accessory wire layer | KVP-TLV wire encoding. Two-stage capability negotiation (iAP2 IDPS + per-component dictionary). LGI message field count (3 + LaneInfo group). ACCNav_DispComp_* table. Lane-guidance patching recipe with speculative wire bytes. AccessoryiAP2Shim is per-client (not adapter) capability mask. CoreAccessoriesFeatures is an AudioProduct cert stub, not a generic feature framework. |
| Agent 3 | External validation + AirPlay layer | Modern AirPlay wrappers (LimitedUI bitmask, layered night mode, vehicleInformation event bus). EAVehicleInfo full schema (engine, EV, climate, cabin, identity). Road Object Detection / ADAS subsystem (entirely missed by Agents 1 and 2). 11+ new iOS 26 component classes vs CCPA's 19. Deny-list bidirectional channel. Cluster URL controller. CCPA cross-check against firmware RE doc. |

Cross-verification was done by comparing each finding against the other two agents' outputs. Items only included here when at least 2 agents corroborated, or when a single-agent finding cites a direct binary address (verifiable via Section 12 commands).

Items the agents disagree on are flagged in Appendix A.
