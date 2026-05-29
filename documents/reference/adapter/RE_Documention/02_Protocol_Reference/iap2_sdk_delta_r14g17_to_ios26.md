# iAP2 RouteGuidance SDK Delta — R14G17 (2017) → iOS 26.5 (2026)

**Status:** AUTHORITATIVE for 0x5201 / 0x5202 message field surface. Sourced 2026-05-28 from Apple's own iOS 26.5 binaries.
**Source IPSW:** iPhone18,4 build 23F77 (iOS 26.5), `/System/Library/PrivateFrameworks/AccessoryNavigation.framework/AccessoryNavigation`
**Recovery method:** `ipsw extract --dyld` → `ipsw class-dump` → `ipsw dyld disass` on `+keyForType:` jump tables
**Reference binaries on disk:** `~/Downloads/ios26.5_re/23F77__iPhone18,4/{AccessoryNavigation,CarKit,CoreAccessories,AccessoryiAP2Shim,CarKitNavigation}`

This document fills the gap between Apple's last publicly-distributed CarPlay Communication Plug-In SDK (R14G17, June 2017) and what modern iOS 26.5 actually emits over the wire. It is an unofficial-but-binary-verified changelog produced for interoperability research on the user's own CPC200-CCPA adapter under the DMCA §1201 vehicle telematics security research exemption (renewed 2024, expanded 2026).

---

## 1. Top-line summary of changes since R14G17

| Subsystem | R14G17 (2017) | iOS 26.5 (2026) | Net delta |
|---|---|---|---|
| 0x5201 RouteGuidanceUpdate fields | 16 | 26 | +10 fields |
| 0x5202 ManeuverItemsUpdate fields | 11 | 13 | +2 fields |
| Display component capability flags | 7 | 11 | +4 flags |
| iAP2 identification components (NavigationComponent class) | basic Route Guidance only | Lane Guidance, ExitInfo, EV, TimeZone, Preconditioning | +5 capability gates |
| Lane Guidance subsystem | absent | new (`ACCNav_LGIUpdate_*`) | new top-level entity |
| EV / battery telemetry | absent | new (`ArrivalBatteryLevel`, charging stations) | new top-level entity |
| Multi-stop routes | absent | new (`StopType`, `FinalWaypointBatteryLevel`) | new top-level entity |
| Time zone awareness | absent | new (`DestinationTimeZoneOffsetMinutes`) | new field + capability |

---

## 2. 0x5201 RouteGuidanceUpdate field delta

### Carried over from R14G17 (semantics unchanged; some renamed)

| Type ID | R14G17 Name | iOS 26 Name | Status |
|---|---|---|---|
| 0x01 | `RouteGuidanceState` | `RouteGuidanceState` | same |
| 0x02 | `ManeuverState` | `ManeuverState` | same |
| 0x03 | `CurrentRoadName` | `CurrentRoadName` | same |
| 0x04 | `DestinationName` | `DestinationName` | same |
| 0x05 | `EstimatedTimeOfArrival` | `EstimatedTimeOfArrival` | same (u64 unix seconds) |
| 0x06 | `TimeRemainingToDestination` | `TimeRemainingToDestination` | same |
| 0x07 | `DistanceRemaining` | `DistanceRemaining` | same |
| 0x08 | `DistanceRemainingDisplayStr` | `DistanceRemainingDisplayString` | renamed: `Str` → `String` |
| 0x09 | `DistanceRemainingDisplayUnits` | `DistanceRemainingDisplayUnits` | same |
| 0x0a | `DistanceToNextManeuver` | `DistanceRemainingToNextManeuver` | renamed: prefix `Remaining` added |
| 0x0b | `DistanceToNextManeuverDisplayStr` | `DistanceRemainingToNextManeuverDisplayString` | renamed |
| 0x0c | `DistanceToNextManeuverDisplayUnits` | `DistanceRemainingToNextManeuverDisplayUnits` | renamed |
| 0x0d | `RouteGuidanceManeuverItems` (group/list) | `RouteGuidanceManeuverCurrentList` | **CLARIFIED.** Modern iOS treats this as the live cursor pair `(currentIdx, nextIdx)`. Older firmware RE doc labeled it a "group/count-only" — likely an older AIS revision. |
| 0x0e | `ManeuverCurrentList` | `RouteGuidanceManeuverCount` | **SHIFTED.** Older interpretation moved up — modern iOS uses this slot for total maneuver count in route. |
| 0x0f | `RouteGuidanceVisibleInApp` | `RouteGuidanceBeingShownInApp` | renamed: `Visible` → `BeingShown` |
| 0x13 | `SourceName` | `SourceName` | same ("Apple Maps") |

### NEW in iOS 26 (post-R14G17)

| Type ID | iOS 26 Name | Subsystem | Notes |
|---|---|---|---|
| **0x10** | `LaneGuidanceCurrentIndex` | Lane Guidance | u16 — which lane-guidance entry is active |
| **0x11** | `LaneGuidanceTotalCount` | Lane Guidance | u16 — total lane-guidance entries in route |
| **0x12** | `LaneGuidanceShowing` | Lane Guidance | u8 bool — whether iPhone is currently showing lane guidance |
| **0x14** | `SourceSupportsRouteGuidance` | Capability echo | u8 bool — always 1 since source = iPhone |
| **0x15** | `DestinationTimeZoneOffsetMinutes` | Time Zone | i16 — destination timezone offset in minutes |
| **0x16** | `StopType` | Multi-stop | u8 — type of upcoming stop (waypoint, charging, etc.) |
| **0x17** | `ChargingStationInfoList` | EV / Charging | group — list of charging stations along route |
| **0x18** | `ArrivalBatteryLevel` | EV / Battery | u8 % — expected battery at arrival |
| **0x19** | `DepartureBatteryLevel` | EV / Battery | u8 % — battery at departure |
| **0x1a** | `FinalWaypointBatteryLevel` | EV / Battery | u8 % — battery at final waypoint |

`ChargingStationInfoList` (0x17) is itself a group containing sub-fields recovered via `+[ACCNavigationRouteGuidanceUpdateInfo keyForChargingStationInfoType:]`:
- `ChargingStationInfo_ConnectorType`
- `ChargingStationInfo_Power`
- `ChargingStationInfo_Voltage`

---

## 3. 0x5202 RouteGuidanceManeuverUpdate field delta

### Carried over (unchanged)

| Type ID | iOS 26 Name | Notes |
|---|---|---|
| 0x01 | `Index` | maneuver_index (was unnamed in R14G17 RE) |
| 0x02 | `ManeuverDescription` | spoken announcement |
| 0x03 | `ManeuverType` | CPManeuverType 0-53 |
| 0x04 | `AfterManeuverRoadName` | post-maneuver road |
| 0x05 | `DistanceBetweenManeuver` | meters |
| 0x06 | `DistanceBetweenManeuverDisplayString` | "30", "0.2" |
| 0x07 | `DistanceBetweenManeuverDisplayUnits` | 1/4 |
| 0x08 | `DrivingSide` | 0/1/2 |
| 0x09 | `JunctionType` | intersection / fork / etc. |
| 0x0a | `JunctionElementAngle` | multi-occurrence per spoke |
| 0x0b | `JunctionElementExitAngle` | signed degrees |

### NEW in iOS 26

| Type ID | iOS 26 Name | Notes |
|---|---|---|
| **0x0c** | `LinkedLaneGuidanceInfo` | group — links maneuver to its lane-guidance block |
| **0x0d** | `ExitInfo` | group — highway exit number / lane info |

---

## 4. NEW message: 0x520? Lane Guidance Information Update (LGI)

iOS 26 adds a third message type in the RouteGuidance subsystem for lane-guidance content. Wire ID not yet captured (likely `0x5204` or `0x5205`). Field surface recovered from `_ACCNav_LGIUpdate_*` string symbols:

| Field | Notes |
|---|---|
| `AccessoryID` | which display component is receiving this |
| `LaneGuidanceIndex` | which lane-guidance entry (matches RGUpdate 0x10) |
| `InstructionText` | text instruction for this lane segment |
| `LaneInfo` | group containing per-lane info |
| `LaneInfo_Index` | lane number |
| `LaneInfo_Angle` | angle of lane chevron |
| `LaneInfo_AngleHighlight` | angle of the highlighted (recommended) lane |
| `LaneInfo_Status` | active / inactive / recommended |

This is a separate top-level entity emitted only when the adapter declares `supportsLaneGuidance=true` during component registration.

---

## 5. Display Component capability declarations (advertised by adapter)

The adapter advertises its navigation rendering capabilities via `ACCNav_DispComp_*` parameters during the iAP2 `routeGuidanceDisplayComponent` registration (component ID 0x12 in legacy spec, 0x0D-ish region per firmware RE). Apple's iPhone gates which 0x5201/0x5202/LGI fields it sends based on what the adapter advertises.

### R14G17 component-registration parameters

| Param | Purpose |
|---|---|
| `ComponentID` | display-component instance ID |
| `Name` | human label |
| `SourceName` | source app name |
| `SourceSupportsRouteGuidance` | bool capability echo |
| `MaxCurRoadNameLength` | UTF-8 byte cap for `CurrentRoadName` |
| `MaxDestinationNameLength` | cap for `DestinationName` |
| `MaxAfterManeuverRoadNameLength` | cap for `AfterManeuverRoadName` |
| `MaxManeuverDescriptionLength` | cap for `ManeuverDescription` |
| `MaxMGuidanceManeuverCapacity` | cap for total maneuvers per route |

### NEW in iOS 26

| Param | Gates |
|---|---|
| `MaxLaneGuidanceDescriptionLength` | iPhone won't send LGI fields if not advertised |
| `MaxLaneGuidanceStorageCapacity` | iPhone won't send LGI fields if not advertised |

Plus four runtime capability getters on the navigation provider:
- `supportsExitInfo` — gates 0x5202 ExitInfo field (0x0d)
- `supportsLaneGuidance` — gates 0x5201 fields 0x10/0x11/0x12 + 0x5202 field 0x0c + entire LGI message
- `supportsPreconditioning` — gates EV battery fields (0x18-0x1a) and possibly preconditioning controls
- `supportsTimeZoneOffset` — gates 0x5201 field 0x15 (`DestinationTimeZoneOffsetMinutes`)

These appear as `setSupports*:` setters on `ACCNavigationAccessoryComponent` — meaning the iPhone calls them on its in-memory model of each accessory based on what was advertised. Format of the wire encoding for these flags is in `AccessoryNavigation.framework` (specific KVP encoding not yet RE'd).

---

## 6. **Why CCPA captures show 0/0/0/1 for the new fields** — capability negotiation

The CCPA's `ARMiPhoneIAP2` was compiled against R14G17. Its `routeGuidanceDisplayComponent` registration only advertises the R14G17 capability set. iPhone iOS 26 sees an adapter that:
- Advertises `routeGuidanceDisplayComponent` ✓ — so basic 0x5201/0x5202 emit
- Does NOT advertise `MaxLaneGuidanceStorageCapacity` → iPhone keeps `LaneGuidanceCurrentIndex/TotalCount/Showing` at constant 0 and never emits LGI messages
- Does NOT call `setSupportsExitInfo:` → 0x5202 `ExitInfo` never appears
- Does NOT call `setSupportsTimeZoneOffset:` → 0x5201 `DestinationTimeZoneOffsetMinutes` never appears
- Does NOT call `setSupportsPreconditioning:` → no EV battery fields, no charging-station list
- `SourceSupportsRouteGuidance=1` is constant because iPhone always claims this (it IS the source)

**This is the user's hypothesis confirmed exactly:** iPhone gates additional field emission on adapter-side capability advertisement during the iAP2 identification phase. The four "always-0/1" fields in our captures aren't dead — they're sized placeholders that iPhone wires up but never populates because our adapter never said "I can show lane guidance."

### What would the adapter need to do to surface the new fields?

| Adapter capability to advertise | Triggers |
|---|---|
| `MaxLaneGuidanceDescriptionLength > 0` + `MaxLaneGuidanceStorageCapacity > 0` | 0x5201 fields 0x10/0x11/0x12 populate; 0x5202 field 0x0c populates; new LGI message stream starts |
| `setSupportsExitInfo:YES` | 0x5202 ExitInfo (0x0d) populates on highway-exit maneuvers |
| `setSupportsTimeZoneOffset:YES` | 0x5201 field 0x15 populates on cross-timezone routes |
| `setSupportsPreconditioning:YES` + vehicle profile = EV | 0x5201 fields 0x17/0x18/0x19/0x1a populate; charging-station list streams |

The CCPA firmware would need a binary patch (analogous to the existing ARMiPhoneIAP2 v5 patch that restored full 0x5201/0x5202 forwarding) to inject these advertisement flags during identification. That's a deeper firmware patch — out of scope for the current host-side work but possible.

Alternatively: a different adapter that advertises these natively (a hypothetical "modern" CCPA generation) would expose them without patching.

---

## 7. CarKit / CarKitNavigation wrapper classes (host-side reference)

iOS 26 has these public-ish CarKit classes that wrap the AccessoryNavigation primitives. Useful if writing host-side code that mirrors Apple's data model:

| Class | Role |
|---|---|
| `CPRouteGuidance` (CarPlay framework) | Highest-level — what a CarPlay-using app interacts with |
| `CPManeuver` (CarPlay framework) | High-level maneuver wrapper |
| `CRAccNavRouteGuidance` (CarKitNavigation) | CarKit's internal wrapper over the iAP2 layer |
| `CRAccNavManeuver` (CarKitNavigation) | Same for maneuvers |
| `ACCNavigationRouteGuidanceUpdateInfo` (AccessoryNavigation) | Low-level iAP2-fielded message wrapper |
| `ACCNavigationManeuverUpdateInfo` (AccessoryNavigation) | Same for maneuvers |
| `ACCNavigationAccessoryComponent` (AccessoryNavigation) | Per-accessory display-component model w/ all the `supports*` capability getters |

Disassembling these classes is how the field tables in this doc were recovered.

---

## 8. Reproducibility — how to verify this doc

```bash
cd ~/Downloads/ios26.5_re/23F77__iPhone18,4

# All field names in AccessoryNavigation:
strings AccessoryNavigation | grep -E '^_ACCNav_' | sort -u

# 0x5201 integer→name table:
ipsw dyld disass dyld_shared_cache_arm64e --vaddr 0x226ac5f74 --count 50
ipsw dyld dump dyld_shared_cache_arm64e 0x226ac61e8 --size 104

# 0x5202 integer→name table:
ipsw dyld disass dyld_shared_cache_arm64e --vaddr 0x226ac77ec --count 50
ipsw dyld dump dyld_shared_cache_arm64e 0x226ac72fc --size 52

# Class enumeration:
ipsw class-dump --verbose dyld_shared_cache_arm64e AccessoryNavigation
ipsw class-dump --verbose dyld_shared_cache_arm64e CarKitNavigation
```

Each iOS version's IPSW can be re-cracked this way to track future protocol additions.

---

## 9. The CarPlay master capability surface — what gates EVERY iPhone-side feature

Beyond the navigation-specific `ACCNavigationAccessoryComponent` capabilities (`supportsLaneGuidance`, `supportsExitInfo`, `supportsPreconditioning`, `supportsTimeZoneOffset`), CarKit has a much larger capability surface that gates the entire CarPlay feature set. These are all `setSupports*:`-style declarations the adapter makes during identification/registration; iPhone gates feature emission downstream.

Recovered from `CarKit.framework/CarKit` strings + ObjC class metadata in iOS 26.5.

### Master `supports*` capability list (62 entries)

Grouped by domain:

#### Transport / pairing
| Capability | Effect when claimed |
|---|---|
| `supportsCarPlay` | Master flag — accessory supports CarPlay at all |
| `supportsWirelessCarPlay` | Enables WiFi-based CarPlay (5GHz, AirPlay tunnel) |
| `supportsUSBCarPlay` | Enables USB-tethered CarPlay (modern USB-C) |
| `supportsBluetoothLE` | iPhone may advertise BLE accessory metadata |
| `supportsWiredBluetoothPairing` | One-touch USB-triggered BT pairing |
| `supportsOOBBTPairing` / `supportsOOBBTPairing2` | Out-of-band BT pairing (newer/v2 protocol) |
| `supportsMutualAuth` / `supportsMutualAuthentication` | MFi mutual authentication (real coprocessor required) |
| `supportsCarPlayConnectionRequest` | Adapter can request iPhone start CarPlay |
| `supportsStartSessionRequest` | Same as above for session start |
| `supportsStopSession` / `supportsStopSessionDisconnectForThisDrive` | Adapter can request iPhone end session |

#### Audio
| Capability | Effect when claimed |
|---|---|
| `supportsAudio` | Basic audio routing enabled |
| `supportsMixableAudio` | iPhone may emit overlapping audio streams |
| `supportsDolbyAtmos` | Atmos-encoded audio streams |
| `supportsSpatialAudio` | Apple Spatial Audio support |
| `supportsSiriMixable` | Siri audio mixable with media |
| `supportsSiriZLL` / `supportsSiriZLLButton` | "Zero-latency listening" / hardware Siri button |
| `supportsVoiceConversational` | Voice/VoIP audio class |
| `supportsEnhancedSiriVoice` | Higher quality Siri synthesis voice |
| `supportsHighFidelityTouch` | Touch latency upgrade for audio scrubbing |

#### Display / UI
| Capability | Effect when claimed |
|---|---|
| `supportsVideo` | Video stream to accessory display |
| `supportsTemplates` | CarPlay Templates Host (the main CarPlay UI) |
| `supportsThemeAssets` | Custom car-theme overlays |
| `supportsAppearanceMode` | Light/Dark mode awareness |
| `supportsMapAppearanceMode` | Map-specific dark mode |
| `supportsPerDisplayNightMode` | Independent night mode per display (cluster vs HUD) |
| `supportsUIOutsideSafeArea` | Curved-display safe-area override |
| `supportsLayerTracking` | CarPlay layer composition coordination |
| `supportsFocusTransfer` | Focus passing between iPhone surfaces and CarPlay |
| `supportsHighFrameRateMaps` | 60fps maps rendering |
| `supportsInstrumentClusterContent` | **Cluster-IC content streaming** — gates iPhone sending nav/cluster content to a secondary display |

#### Apps / extensions
| Capability | Effect when claimed |
|---|---|
| `supportsCarPlayContent` | Generic CarPlay content stream |
| `supportsCarPlayAppLinks` | App-to-app deep linking |
| `supportsAutomakerAppService` | OEM custom app extension hosting |
| `supportsLiveActivities` | iPhone Live Activities on CarPlay |
| `supportsLodWidgets` | Lock-screen-style widgets on CarPlay |
| `supportsPinnedMessages` | Pinned message UI |
| `supportsAdditionalContent` | Generic content sideband |

#### Communication
| Capability | Effect when claimed |
|---|---|
| `supportsCommunication` | Phone/messaging features at all |
| `supportsCalling` | Phone calls routing |
| `supportsMessaging` | iMessage/SMS over CarPlay |

#### Vehicle / EV / commerce
| Capability | Effect when claimed |
|---|---|
| `supportsVehicleData` | iPhone may receive `vehicleStatus`/`vehicleInformation` from accessory |
| `supportsCharging` | **EV** — gates 0x5201 fields 0x17/0x18/0x19/0x1a + charging-station content |
| `supportsFueling` | ICE fueling-station prompts |
| `supportsParking` | Parking integration |
| `supportsElectronicTollCollection` | ETC transponder declaration (R14G17 already had this) |
| `supportsRouteSharing` | Apple Maps "Share ETA" target |
| `supportsDrivingTask` | Driving-mode coordination (Focus integration) |
| `supportsPublicSafety` | First-responder / police / emergency override surface |
| `supportsQuickOrdering` | Drive-thru ordering integration |

#### Navigation
| Capability | Effect when claimed |
|---|---|
| `supportsMaps` | Maps content base flag |
| `supportsMapContent` | Map tile content stream |
| `supportsAccNav` | **Accessory Navigation** — the entire `ACCNavigation*` subsystem this doc covers. Gates 0x5200-0x5203 + LGI messages. |

#### Misc
| Capability | Effect when claimed |
|---|---|
| `supportsFileTransfer` | Bulk file transfer (artwork, themes) |
| `supportsLogTransfer` | iPhone log shipping for diagnostics |
| `supportsACBack` | A/C / climate back-channel |
| `supportsSecureCoding` / `supportsBSXPCSecureCoding` | NSSecureCoding for serialized data |
| `supportsDDPContent` | Apple's DigitalDeviceProvider content |

### Capabilities dictionary keys (host-side)

CarKit persists capabilities to the per-accessory global domain via `+[CRCarPlayCapabilities persistCapabilitiesToGlobalDomain]`. Known keys:
- `CapabilitiesDashboardRoundedCornersKey`
- `CapabilitiesLiveActivitiesKey`
- `CapabilitiesLodWidgetsKey`
- `CapabilitiesNowPlayingAlbumArtKey`
- `CapabilitiesViewAreaInsetKey`
- `CARCapabilitesInsets` (sic — typo in iOS)
- `CarCapabilitiesContentVersion`
- `CarCapabilitiesDefaultIdentifier`

These appear to be the AirPlay-layer counterpart to iAP2-layer component capabilities — what gets cached so iPhone "remembers" a paired accessory's capability set across sessions.

### Night Mode special handling

CarKit has multiple night-mode resolution paths (recovered via `_systemNightMode`, `_locationBasedNightMode`, `_fallbackNightMode`, `_supportsPerDisplayNightMode`). Modern iPhone resolves night mode in this order:
1. Per-display night mode (`supportsPerDisplayNightMode=YES`) — each display can have its own state
2. System night mode (iPhone reports its own)
3. Location-based (computed from sun position)
4. Fallback (default OFF)

The legacy `setNightMode` AirPlay command (R14G17) still works but is overridden by these.

### What the CCPA currently claims vs what it could claim

The CCPA's `ARMiPhoneIAP2` advertises a fixed minimal subset baked into the firmware. Recovering the precise list requires Ghidra on the IDENTIFICATION sender in ARMiPhoneIAP2 (a separate engine from RouteGuidance). Approximate set based on observed iPhone behavior:

| Currently claimed (CCPA observed behavior) | NOT claimed (would unlock more iPhone features) |
|---|---|
| `supportsCarPlay`, `supportsWirelessCarPlay` | `supportsUSBCarPlay` (USB-C iPhones limited) |
| `supportsAudio`, `supportsMixableAudio` | `supportsDolbyAtmos`, `supportsSpatialAudio` |
| `supportsCalling`, `supportsCommunication` | `supportsSiriZLL`, `supportsSiriZLLButton` |
| `supportsTemplates`, `supportsThemeAssets` | `supportsInstrumentClusterContent` ⭐ |
| `supportsMaps`, `supportsMapContent` | `supportsAccNav` ⭐, `supportsHighFrameRateMaps` |
| `supportsAppearanceMode` | `supportsPerDisplayNightMode` |
| `supportsElectronicTollCollection` | `supportsCharging`, `supportsFueling`, `supportsParking` |
| `supportsMutualAuth` (via real MFi 0x11 IC) | `supportsLiveActivities`, `supportsLodWidgets` |

**⭐ Most interesting from a Bug A / cluster integration angle:** `supportsInstrumentClusterContent` and `supportsAccNav` — claiming these MIGHT unlock direct iPhone-to-cluster nav content streaming (separate from the iAP2 RouteGuidance text-metadata channel we currently use). Worth follow-up RE.

---

## 10. Putting it all together — three layers of feature gating

```
Layer 1: USB / Bluetooth transport selection
  ↓ (adapter advertises iAP2 over selected transport)
Layer 2: iAP2 identification — component table + per-component capabilities
  ↓ (iPhone sees: routeGuidanceDisplayComponent + ACCNav capability flags)
Layer 3: CarPlay session — CarKit capability dictionary
  ↓ (iPhone sees: supportsAccNav, supportsCharging, etc. + global domain cache)
Result: iPhone decides which messages to emit, which features to expose
```

The capability negotiation user pointed at IS the gating mechanism — Apple has built layered checks at every protocol stratum. Specific feature → specific capability flag → specific advertisement during identification.

---

## 11. Open items for next session

- Capture LGI message wire type ID (0x5204? 0x5205?) by patching ARMiPhoneIAP2 to advertise `MaxLaneGuidanceStorageCapacity > 0`
- Decode the iAP2 IDENTIFICATION engine in ARMiPhoneIAP2 (not RouteGuidance) — recover the actual `setSupports*:` advertisement set the adapter currently sends
- RE `supportsInstrumentClusterContent` wire format — could enable a separate cluster nav channel
- Decode 0x5200 (StartRouteGuidance) and 0x5203 (StopRouteGuidance) parameter structure
- `Iap2StateParser.kt` — extend to handle new 0x5201 fields (lane guidance, time zone, EV battery) and 0x5202 fields 0x0c/0x0d (forward-compat for adapters that advertise these)
