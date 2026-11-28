# LeadPipe

LeadPipe is an independently maintained Android fork of PipePipe. It syncs changes from PipePipe and contributes fixes upstream.

Find the current APKs and their exact source manifests on [GitHub Releases](https://github.com/darvilp/LeadPipe/releases). Report LeadPipe app issues on the [LeadPipe issue tracker](https://github.com/darvilp/LeadPipe/issues).

LeadPipe uses four-part versions. In `5.6.0.24`, `5.6.0` is the PipePipe base and `24` is the LeadPipe release number. The counter keeps increasing across PipePipe updates. See the [versioning policy](release/README.md#versioning).

## LeadPipe and PipePipe

See the [Subscription Groups guide](docs/wiki/Subscription-Groups.md) and the [wiki comparison draft](docs/wiki/LeadPipe-vs-PipePipe.md).

Rows marked **CI candidate** are included in the [LeadPipe 5.6.0.38 candidate](https://github.com/darvilp/LeadPipe/commits/candidate/leadpipe-5.6.0.38/).

### Features

| Feature | LeadPipe | PipePipe 5.6.0 |
| --- | --- | --- |
| Browsing while playing | Browse other videos while the active queue keeps playing. Choose whether to play now and keep the queue, add to the end, or replace it. | Opening or playing another video can replace the active queue. |
| Add to queue from playback buttons | Long-press Background or Popup on video details, or a playback button in a list header, to append and keep the active player mode. | Use the queue actions in item menus or the detail view. |
| Quick Enqueue | Tap the enqueue button on a video row to add it to the end. Hold it to play the video next. Enqueue starts background playback if the queue is empty. | Enqueue and Enqueue next are available in item menus. |
| Switch playback modes | Move the active session between main, background and popup playback while keeping the queue, position and play/pause state. | Mode switching is available from some player controls, such as background to main or popup. |
| Open details from the playing title | **CI candidate.** Tap the title in fullscreen or on the Play Queue screen to open the current video's details while keeping the queue and playback position. | Expand the mini-player or open Details from a queue row. |
| Queue playtime | See elapsed and total media time in the full queue and in the player's queue panel. Queues with more pages show a subtotal for the loaded entries. A plus sign marks a lower bound when a duration is unknown. | The play queue shows individual entries and their durations. |
| Remaining queue time | **CI candidate.** Tap the playtime summary in either queue view to switch between elapsed and remaining media time. | The play queue shows individual entries and their durations. |
| Remaining playback time | Tap the timer on the right of the player to switch between total duration and remaining media time for the current video. | The player shows the current position and total duration. |
| Restore the play queue | Reopen LeadPipe to restore the active queue paused at the saved position. Use **Clear queue** in the Play Queue menu to discard it. | The active queue is kept for the player session. |
| Undo a queue removal | **CI candidate.** Tap **Undo** after swiping away an entry or choosing **Remove** to put it back beside its previous neighbors. | Swipe or use the item menu to remove an entry. |
| Remove before current | **CI candidate.** Use the Play Queue menu to remove every entry before the current one after confirming the count. | Swipe or use the item menu to remove individual entries. |
| Move to play next | **CI candidate.** Long-press an existing queue entry and choose **Move to play next**. | Drag a queued entry to change its position. |
| Subscription-group content | Choose videos, Shorts and live streams for each group, with per-channel overrides. | Subscription groups organize channels. |
| Feed refresh | Keep browsing the existing feed while newer items load. | The feed list is hidden while refresh is in progress. |
| SponsorBlock editing | Edit exact segment times and keep unfinished drafts tied to their video while browsing. | Enter exact segment times in the editor for the current video. |
| SponsorBlock servers | Set separate HTTPS server URLs for YouTube and BiliBili in SponsorBlock settings. Leave either field blank to use its default server. | Each service uses its default SponsorBlock server. |
| Check for updates | **CI candidate.** Check from **About** or **Settings → Updates**. | Check from **Settings → Updates**. |
| Error reports | **CI candidate.** Copy a formatted report with the device make and model, build details and report orientation, or open the LeadPipe bug form to add reproduction steps. | Reports include app and Android versions, request details and the stack trace. |

### Fixes

| Fix | What changed | Upstream status |
| --- | --- | --- |
| Experimental UI thumbnails | Fixes thumbnails that could stay blank in search and other lists until you scrolled away and back. | Open [#136](https://github.com/InfinityLoop1308/PipePipeClient/pull/136) |
| SponsorBlock crash | Fixes a crash that could occur when pressing Android Home during playback. | Open [#131](https://github.com/InfinityLoop1308/PipePipeClient/pull/131) |
| Queue and playlist dragging | Keeps the list steady and prevents extra reorders when dragging near the edges. | **Merged upstream** in PipePipe 5.6.0 [#118](https://github.com/InfinityLoop1308/PipePipeClient/pull/118) |
| Long comments with links | Tap the text to collapse a long comment, including replies. Links remain tappable. | LeadPipe fix |
| Play Queue in landscape | Fixes a crash opening the full queue in landscape and shows elapsed and total playtime above the list. | LeadPipe fix |
| Rapid swipe and Undo | Fixes a crash in the queue list during rapid removals and Undo. | **CI candidate** |

## Build and release

Use `python3 tools/release.py --check` to verify signing, toolchain and source pins, then `python3 tools/release.py` to build and export a signed local candidate. The [release runbook](release/README.md) records acceptance and publication steps.

## Upstream projects and license

LeadPipe builds on [PipePipe](https://codeberg.org/NullPointerException/PipePipe), which is based on [NewPipe](https://github.com/TeamNewPipe/NewPipe). See the [GNU General Public License](LICENSE).
