# LeadPipe vs. PipePipe

For setup steps and an example with channel exceptions, see [Subscription Groups](Subscription-Groups.md).

## Features

| Feature | LeadPipe | PipePipe 5.6.0 |
| --- | --- | --- |
| Browsing while playing | Browse other videos while the active queue keeps playing. Choose whether to play now and keep the queue, add to the end, or replace it. | Opening or playing another video can replace the active queue. |
| Add to queue from playback buttons | Long-press Background or Popup on video details, or a playback button in a list header, to append and keep the active player mode. | Use the queue actions in item menus or the detail view. |
| Switch playback modes | Move the active session between main, background and popup playback while keeping the queue, position and play/pause state. | Mode switching is available from some player controls, such as background to main or popup. |
| Subscription-group content | Choose videos, Shorts and live streams for each group, with per-channel overrides. | Subscription groups organize channels. |
| Feed refresh | Keep browsing the existing feed while newer items load. | The feed list is hidden while refresh is in progress. |
| SponsorBlock editing | Edit exact segment times and keep unfinished drafts tied to their video while browsing. | Enter exact segment times in the editor for the current video. |

## Fixes

| Fix | What changed | Upstream status |
| --- | --- | --- |
| Experimental UI thumbnails | Fixes thumbnails that could stay blank in search and other lists until you scrolled away and back. | Open [#136](https://github.com/InfinityLoop1308/PipePipeClient/pull/136) |
| SponsorBlock crash | Fixes a crash that could occur when pressing Android Home during playback. | Open [#131](https://github.com/InfinityLoop1308/PipePipeClient/pull/131) |
| Queue and playlist dragging | Keeps the list steady and prevents extra reorders when dragging near the edges. | **Merged upstream** in PipePipe 5.6.0 [#118](https://github.com/InfinityLoop1308/PipePipeClient/pull/118) |
