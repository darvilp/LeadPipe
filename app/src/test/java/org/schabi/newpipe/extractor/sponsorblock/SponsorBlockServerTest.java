package org.schabi.newpipe.extractor.sponsorblock;

import org.junit.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SponsorBlockServerTest {
    @Test
    public void blankDefaultsAndUrlValidation() {
        final int youtubeId = ServiceList.YouTube.getServiceId();
        final int biliBiliId = ServiceList.BiliBili.getServiceId();
        assertEquals("https://sponsor.ajay.app/api/", SponsorBlockExtractorHelper.getApiUrl(youtubeId));
        assertEquals("https://bsbsb.top/api/", SponsorBlockExtractorHelper.getApiUrl(biliBiliId));
        assertEquals("https://example.test/api/",
                SponsorBlockExtractorHelper.normalizeApiUrl(" https://example.test "));
        assertEquals("https://example.test:8443/sb/api/",
                SponsorBlockExtractorHelper.normalizeApiUrl("https://example.test:8443/sb/api"));
        assertNull(SponsorBlockExtractorHelper.normalizeApiUrl(" "));
        assertNull(SponsorBlockExtractorHelper.normalizeApiUrl("http://example.test"));
        assertNull(SponsorBlockExtractorHelper.normalizeApiUrl("https://user@example.test"));
        assertNull(SponsorBlockExtractorHelper.normalizeApiUrl("https://example.test/?token=test"));
        assertNull(SponsorBlockExtractorHelper.normalizeApiUrl("https://example.test/#fragment"));
    }

    @Test
    public void customServerRoutesLookupsSubmissionsAndVotesWithoutPublicRetry() throws Exception {
        final var youtube = ServiceList.YouTube;
        final var biliBili = ServiceList.BiliBili;
        final var oldDownloader = NewPipe.getDownloader();
        final var oldLocalization = NewPipe.getPreferredLocalization();
        final var oldCountry = NewPipe.getPreferredContentCountry();
        final var oldYouTubeSettings = youtube.getSponsorBlockApiSettings();
        final var oldBiliBiliSettings = biliBili.getSponsorBlockApiSettings();
        final var settings = new SponsorBlockApiSettings();
        settings.includeSponsorCategory = true;
        settings.youTubeApiUrl = "https://youtube.example.test";
        settings.biliBiliApiUrl = "https://bilibili.example.test/sb/api";
        final var downloader = new RecordingDownloader();
        NewPipe.init(downloader);
        youtube.setSponsorBlockApiSettings(settings);
        biliBili.setSponsorBlockApiSettings(settings);
        try {
            final String videoId = "dQw4w9WgXcQ";
            final String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
            final var youtubeExtractor = youtube.getStreamExtractor(videoUrl);
            final var segment =
                    SponsorBlockExtractorHelper.getSegments(youtubeExtractor, settings)[0];
            assertTrue(downloader.last().url()
                    .startsWith("https://youtube.example.test/api/skipSegments/"));
            assertTrue(downloader.last().url().contains("userAgent="));

            final var biliBiliExtractor =
                    biliBili.getStreamExtractor("https://www.bilibili.com/video/BV1xx411c7mD");
            final var biliBiliSegment =
                    SponsorBlockExtractorHelper.getSegments(biliBiliExtractor, settings)[0];
            assertTrue(downloader.last().url()
                    .startsWith("https://bilibili.example.test/sb/api/skipSegments/"));
            assertFalse(downloader.last().url().contains("userAgent="));
            assertEquals(List.of("PipePipe"), downloader.last().headers().get("Origin"));

            final var updatedSettings = new SponsorBlockApiSettings();
            updatedSettings.includeSponsorCategory = true;
            updatedSettings.youTubeApiUrl = "https://other.example.test";
            updatedSettings.biliBiliApiUrl = " ";
            youtube.setSponsorBlockApiSettings(updatedSettings);
            biliBili.setSponsorBlockApiSettings(updatedSettings);
            assertEquals("https://bsbsb.top/api/",
                    SponsorBlockExtractorHelper.getApiUrl(biliBili.getServiceId()));

            SponsorBlockExtractorHelper.submitSponsorBlockSegmentVote(segment, 1, "test-user");
            assertTrue(downloader.last().url()
                    .startsWith("https://youtube.example.test/api/voteOnSponsorTime?"));
            SponsorBlockExtractorHelper.submitSponsorBlockSegmentVote(
                    biliBiliSegment, 1, "test-user");
            assertTrue(downloader.last().url()
                    .startsWith("https://bilibili.example.test/sb/api/voteOnSponsorTime?"));
            assertEquals(List.of("PipePipe"), downloader.last().headers().get("Origin"));

            final var info = new StreamInfo(youtube.getServiceId(), videoId, videoUrl, "test");
            SponsorBlockExtractorHelper.submitSponsorBlockSegment(info, segment,
                    SponsorBlockExtractorHelper.getApiUrl(info), "test-user");
            assertTrue(downloader.last().url()
                    .startsWith("https://other.example.test/api/skipSegments?"));

            downloader.offline = true;
            final int requestCount = downloader.requests.size();
            assertEquals(0, SponsorBlockExtractorHelper
                    .getSegments(youtubeExtractor, updatedSettings).length);
            assertEquals(requestCount + 1, downloader.requests.size());
            assertTrue(downloader.last().url()
                    .startsWith("https://other.example.test/api/skipSegments/"));
        } finally {
            youtube.setSponsorBlockApiSettings(oldYouTubeSettings);
            biliBili.setSponsorBlockApiSettings(oldBiliBiliSettings);
            NewPipe.init(oldDownloader, oldLocalization, oldCountry);
        }
    }

    private static final class RecordingDownloader extends Downloader {
        private final List<Request> requests = new ArrayList<>();
        private boolean offline;

        private Request last() {
            return requests.getLast();
        }

        @Override
        public Response execute(final Request request) throws IOException {
            requests.add(request);
            if (offline) {
                throw new IOException("Simulated connection failure");
            }
            return new Response(200, "OK", null, """
                    [{"videoID":"dQw4w9WgXcQ","segments":[{"UUID":"youtube-segment",
                    "category":"sponsor","actionType":"skip","segment":[1,2]}]},
                    {"videoID":"BV1xx411c7mD","segments":[{"UUID":"bilibili-segment",
                    "category":"sponsor","actionType":"skip","segment":[1,2]}]}]
                    """, new byte[0], request.url());
        }

        @Override
        public CancellableCall executeAsync(final Request request, final AsyncCallback callback) {
            throw new UnsupportedOperationException();
        }
    }
}
