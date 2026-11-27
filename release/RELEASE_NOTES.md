# LeadPipe 5.6.0.33

- Add a one-tap enqueue button to video rows. Enqueue starts background playback when the queue is empty.
- Long-press the enqueue button to queue the video immediately after the current item.
- Show elapsed and total playtime on the Play Queue screen and in the player's queue panel.
- Show a loaded total while pages are pending. A trailing plus marks a lower bound when a duration is unknown.
- Tap the right-hand player timer to switch between total duration and remaining media time.
- Fix long comments with links getting stuck open in reply threads. Tap ordinary comment text to collapse it to two lines.
- Restore the active queue paused after restarting LeadPipe. Use **Clear queue** in the Play Queue menu to discard it.
- Fix a crash opening the full Play Queue in landscape and show the elapsed and total playtime above the list.

The PipePipe base is v5.6.0 at client commit `43ef494c1`. This release uses the existing application ID, signing certificate and Room schema 903 for in-place upgrades.

If you are on `5.6.0.24` or later, use **Settings → Updates → Check for updates**. If you are on `5.6.0-unofficial.23` or older, download the APK for your device and install it over your existing app.
