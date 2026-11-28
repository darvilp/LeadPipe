# LeadPipe 5.6.0.37

- Tap the playtime summary in either queue view to switch between elapsed and remaining media time.
- Keep the queue timer and saved playback position tied to the current entry, including when the same video appears more than once.
- Choose **Remove before current** in the full Play Queue menu to trim earlier entries after confirming the count.
- Long-press an existing queue entry and choose **Move to play next** to put it immediately after the current item.
- Tap **Undo** after swiping away an entry or choosing **Remove** in either queue view. Undo restores the most recently removed entry beside its previous neighbors, including when the queue contains duplicate videos.
- Fix a crash in the queue list during rapid swipes and Undo.
- Check for updates directly from **About**.
- Error reports include the device make and model, installed build, and report orientation. **Report on GitHub** opens the bug form for reproduction steps and relevant settings.

The PipePipe base is v5.6.0 at client commit `43ef494c1`. This release uses the existing application ID, signing certificate and Room schema 903 for in-place upgrades.

If you are on `5.6.0.24` or later, use **Settings → Updates → Check for updates**. If you are on `5.6.0-unofficial.23` or older, download the APK for your device and install it over your existing app.
