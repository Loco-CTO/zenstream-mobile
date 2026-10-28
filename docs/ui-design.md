# Android interface design

## Design goal

Every new screen and control should feel like part of the existing ZenStream Android app. People should know their current destination, recognize the next useful action, and return to their media or previous screen without learning custom navigation.

## Start from the existing design

Choose the closest existing screen with the same task before changing UI. Compare the new flow against that screen and reuse its components and state treatments.

| Concern                                             | Existing reference                                                                                                                                                                                                |
| --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Colors, typography, and shapes                      | [Theme](../app/src/main/java/com/zenstream/zenstreammobile/ui/theme/Theme.kt)                                                                                                                                     |
| Main navigation, top/bottom bars, scroll visibility | [ZenStreamApp](../app/src/main/java/com/zenstream/zenstreammobile/ui/navigation/ZenStreamApp.kt)                                                                                                                  |
| Media rows, artwork, and music components           | [MediaComponents](../app/src/main/java/com/zenstream/zenstreammobile/ui/components/MediaComponents.kt) and [MusicComponents](../app/src/main/java/com/zenstream/zenstreammobile/ui/components/MusicComponents.kt) |
| Artwork-derived music colors                        | [ArtworkPalette](../app/src/main/java/com/zenstream/zenstreammobile/ui/components/ArtworkPalette.kt)                                                                                                              |
| Detail overlay and scrollable content               | [DetailScreens](../app/src/main/java/com/zenstream/zenstreammobile/ui/screens/DetailScreens.kt) and [Refreshable](../app/src/main/java/com/zenstream/zenstreammobile/ui/screens/Refreshable.kt)                   |
| Settings and player surfaces                        | [MyPageSettings](../app/src/main/java/com/zenstream/zenstreammobile/ui/screens/MyPageSettings.kt) and [AudioPlayerScreens](../app/src/main/java/com/zenstream/zenstreammobile/ui/screens/AudioPlayerScreens.kt)   |

Reuse the relevant shared composable or extend it for a reusable variation. Ordinary feature work must not introduce a new palette, font scale, navigation model, or button skin. Preserve the app's visual identity while correcting accessibility or interaction defects in the changed area.

## Navigation and task flow

- Preserve the main destinations and their order: Home, Favorites, Library, and My Page. Keep selected states and destination labels consistent.
- Search remains a full-screen dialog opened from the shared top bar on its supported tabs. Dismissal returns to the originating tab. My Page owns its settings and Services subpages; retain its documented reset behavior when reselected or left.
- Put item-specific actions on their owning item/detail surface. Use tabs for peer content, a sheet for a bounded contextual choice, and a screen for a substantial task. Avoid nested sheets/dialogs and duplicate routes to the same settings.
- Respect Android Back and the existing navigation stack: dismiss the current temporary surface before leaving its parent. Preserve relevant list position, filters, and entered data when returning.
- Keep common actions visible and labeled. Gestures, long press, and overflow menus can support secondary actions, but must not be the only discoverable route to an essential task.
- Keep queue and lyrics within the established Now Playing flow. Read [playback](playback.md) and [offline behavior](offline.md) before changing media-related navigation or availability.

## Layout and information density

- Give a screen a clear title and dominant task action. Keep supporting actions close to the content they affect and visually quieter than the primary task.
- Match the nearest screen's padding, item spacing, row height, artwork ratio, corner shapes, and text hierarchy. Reuse existing media rows and settings rows.
- Show the information needed to choose or act now. Put optional detail and advanced settings behind clear labels; keep playback state, download state, and consequential choices visible.
- Use concise, localized labels consistently. Include units and a short explanation only where a setting's meaning or effect is unclear.
- Avoid nested cards, repeated status badges, raw identifiers, and multiple competing accent areas. Do not remove useful context merely to make a screen look sparse.

## Visual language

- Use the existing Material 3 theme roles and shared composable styling. Preserve the app's dark surfaces and established violet accent treatment instead of introducing per-screen colors.
- Music surfaces must use `ArtworkPalette.kt`, which derives colors from the selected Primary artwork's decoded blur hash and provides a fallback. Do not replace it with random colors, media-ID/string-hash colors, or a second palette algorithm.
- Use the generated Lucide resources. Match existing icon size, stroke, tint, fill, and selected-state treatment for the same control.
- Preserve the existing distinction between main, detail, settings, mini-player, and Now Playing surfaces. Android layouts should use Android patterns and remain recognizable within this app.
- Allow translated labels and user text to wrap or expand. Motion should clarify state changes and respect platform animation/accessibility settings.

## Interaction and feedback

- Reflect pressed, selected, pending, success, and error states immediately. Prevent unsafe duplicate actions while keeping Back and recovery available where safe.
- Keep useful content during refresh. Distinguish no media, no matches, offline availability, missing local assets, permission limits, and request failures.
- Retain entered values after failure. Keep labels visible, put errors by the affected field, and use suitable input types and keyboard actions.
- Follow the existing save model and describe changes that require a restart or affect playback. Confirm consequential destructive actions with their specific effect; avoid confirmations for routine movement.
- Use visible explanations for unavailable actions. A disabled icon without context does not explain whether the cause is connectivity, permissions, or missing media.

## Platform behavior and accessibility

- Use Material 3/Compose components and semantics appropriate to the action. Maintain at least 48 dp touch targets with enough separation to avoid overlapping hit areas.
- Give actionable icons localized accessible names. Expose toggle/selection state through semantics, mark purely decorative artwork as decorative, and avoid duplicate TalkBack announcements for a single row.
- Keep TalkBack traversal aligned with the task. Provide accessible alternatives for essential custom gestures and drag operations.
- Preserve shared inset and chrome ownership. Use `DetailOverlayTopBar` for its existing detail flows, `ObserveScrollability` for scrollable surfaces, and the navigation layer's `ScrollVisibilityController`; do not add a second status-bar inset or place controls under system navigation.
- Check compact widths, landscape, text scaling, display cutouts, and the open keyboard. Use available window-size classes when adding supported larger-window layouts; retain the same destination model as navigation adapts.
- Keep text, focus/selection, and controls legible over artwork and gradients. Use text or semantic state as well as color.

## Review checklist

Before calling a UI change complete, review the affected flow and record which checks were actually performed:

- [ ] Name the existing screen/composable used as the reference and compare the changed screen at the same device/window size.
- [ ] A person unfamiliar with the feature can find the main action and identify the current destination.
- [ ] System Back, sheet/dialog dismissal, tab selection, and return state follow existing behavior.
- [ ] Theme roles, spacing, shapes, icons, and music artwork colors match surrounding screens.
- [ ] Loading, empty, offline, error, pending, success, and unavailable states explain what can happen next.
- [ ] Touch targets, TalkBack, text scaling, keyboard/insets, and persistent-player overlap have been reviewed.

A successful APK build alone does not establish visual or navigation acceptance. State any device, visual, or interaction checks that remain unverified.

## Design references

- [Android: Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)
- [Android: accessible component defaults and 48 dp targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Android: adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- [Android: window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)
- [Nielsen Norman Group: usability heuristics](https://www.nngroup.com/articles/ten-usability-heuristics/)
