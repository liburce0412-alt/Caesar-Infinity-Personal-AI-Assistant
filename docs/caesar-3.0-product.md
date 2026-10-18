# Caesar 3.0 homepage and product copy

Status: the direct-250414 change passed focused regressions and isolated phone validation; the phone displayed 250414 and correctly answered four records / 250 minutes. The signed non-debug benchmark was restored at 14:33:24. Production is unchanged and no commit, push, deployment or release occurred. Full accessibility and the remaining 3.0 acceptance scope remain open.

## Homepage

- Retain the existing greeting, art wordmark, shared glass orb, recording action, health details and five-destination Dock.
- Present today's recorded activity first and put the AI entrance directly after it, ahead of health and streak information. The AI entrance now says “回顾今天，理清下一步”; opening it retains the current context and consent controls.
- Replace up to three category chips with one “主要投入” line computed from the largest actual category total. Do not invent a priority, task, course, health value or recommendation when source records are missing.
- Show the real consecutive-recording count as a quiet supporting row. Its component preference remains available, as do the existing today, AI, health and announcements preferences.
- A missing daily greeting no longer injects a generic suggested task. Announcements remain collapsed initially; tree-hole and wish-wall entries stay in the Dock and are not promoted into additional homepage cards.

## Non-AI page copy

- Remove ornamental subtitle lines from time, tree-hole, wish-wall and message-list headers.
- Remove the post-composer subtitle and repeated section instructions; optional topic/image fields remain clearly labelled. Remove the corresponding wish-composer subtitle and redundant date-picker explanation.
- Loading comments use one brief status instead of an additional status card plus an explanation about unpublished drafts.
- Preserve the anonymous-post privacy explanation, opt-in public visibility and moderation information, deletion impact/confirmation, zero-price versus blank-price meaning, error messages and retry actions.
- Distinguish never-synced, last-known-empty, refreshing and confirmed-empty wish-wall states with shorter text. A first-load failure still says that the wall could not be confirmed and offers retry; it does not claim an empty remote catalog.
- Remove the duplicate “已选择” success message after picking a provider model; the selected model and its existing visual state remain available. This copy change itself does not alter routing; the separately authorized routing change is recorded below.

## Free-cloud model selection

- Free text now directly selects `glm-4-flash-250414`; remove the 4.7-first request, its eight-second switching wait and the free-cloud deep-mode entrance. Saved free DEEP state is treated as FAST. Local and personal-cloud modes retain their existing choices.
- Keep the current-turn single-JPEG `glm-4v-flash` image route, actual model display, owner isolation, permissions, confirmations and cancellation. Do not present the direct text model as a fallback or offer free 4.7 deep mode.
- The summary fix was accepted on the phone: four records and 250 minutes, with replay `matchesData=true`. Speech “现在几点” entered the draft without sending; manual send answered Beijing time 14:05:12. Recording cancellation and TTS remain untested.
- The earlier three test conversations and the new route's single test conversation were deleted; signed-in history is empty and legacy history was untouched. The phone now has non-debug benchmark 3.0.0/code14, restored at 14:33:24 with its original signature and first-install time. The profile retains four records / 4h10 and five achievements; the current week-six timetable shows 11 classes. The earlier full 23-course comparison was not repeated in this final round.

## Validation

The new route passed Node 72/72 and seven Android classes 65/65. Replacing affected classes in the combined receipt yields 665/665, not a fresh full suite. Lint has zero errors / 80 warnings; debug and benchmark builds succeeded in 6m2s with the original certificate verified. Phone authentication and upstream each succeeded twice, all upstream calls using 250414; tool replay summary `matchesData=true`. The two App POST completion times were 6773ms / 1403ms, not TTFT. See `artifacts/caesar-3.0-drop47-result.json` and `artifacts/caesar-3.0-drop47-test-cases.json`. Relay listening and reverse forwarding are both cleared; there is no current authorization blocker.

Relevant existing local tests passed in the coordinated run:

```powershell
.\gradlew.bat :apps:android:app:testDebugUnitTest --tests com.campusai.app.HomeFocusFrontendUiTest --tests com.campusai.app.CommunityInteractionUiTest --tests com.campusai.app.CommunityPrivacyUiTest --tests com.campusai.app.MarketEmptyPresentationTest --tests com.campusai.app.WishJournalUiTest --tests com.campusai.app.LocalModelSettingsUiTest
```

The existing empty-presentation expectations were updated for the shorter text. Generated light/dark home images were inspected. Phone navigation through home, AI, time, community and wishes was checked without posting content or changing user records. Full accessibility, recording/health actions and community composer submission were not accepted by those navigation checks.
