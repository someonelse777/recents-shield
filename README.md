# Recents Shield

Blacks out the Recents (Overview) previews of the apps you choose, on Samsung One UI with
3-button navigation. No root needed.

## How it works

- When Recents opens, the app finds each card's preview (`task_scene` on One UI) and
  draws a black box over the previews of the apps you picked.
- Every display frame it re-reads where those previews are, and nudges the boxes ahead
  by the scroll speed so they keep up while you swipe.
- App badges, and cards stacked in front of a hidden card, are cut out of the black so
  they stay visible.
- When you open an app or leave Recents, the overlay disappears.

The boxes are see-through to touches, so tapping a blacked-out card still opens that app.

## Install

1. Every push to `main` builds the app on GitHub. Open this repo's **Releases** page on
   your phone, find **Latest build**, and tap `RecentsShield.apk`.
2. Open the downloaded file and allow installing from your browser when asked.
3. Open **Recents Shield**. Under **Always hidden**, choose apps to hide at all times.
   Under **Schedules**, add rules that hide chosen apps only on certain days and hours
   (a schedule can run past midnight, e.g. 10 PM – 2 AM).
4. Tap **Accessibility settings → Installed apps → Recents Shield** and turn it on.
   - If the switch is greyed out ("Restricted setting"): go to **Settings → Apps →
     Recents Shield → ⋮ (top right) → Allow restricted settings**, then try again.

## If something isn't covered

Open Recents once, then open Recents Shield and tap **Copy diagnostics**. That copies a
description of the Recents screen's layout (app names and positions, no screenshots),
which can be used to tune the detection for your phone.

## Limits

- A very brief flicker is possible while scrolling quickly, since the boxes are redrawn
  about every 30 ms.
- Phone updates that redesign the Recents screen may need the detection adjusted.
