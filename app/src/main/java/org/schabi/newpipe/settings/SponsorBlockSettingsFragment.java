package org.schabi.newpipe.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.sponsorblock.SponsorBlockExtractorHelper;
import org.schabi.newpipe.util.InfoCache;
import org.schabi.newpipe.util.ServiceHelper;

public class SponsorBlockSettingsFragment extends BasePreferenceFragment {
    private final SharedPreferences.OnSharedPreferenceChangeListener sponsorBlockChange =
            (preferences, key) -> {
                if (getString(R.string.sponsor_block_enable_key).equals(key)
                        || getString(R.string.sponsor_block_youtube_api_url_key).equals(key)
                        || getString(R.string.sponsor_block_bilibili_api_url_key).equals(key)) {
                    ServiceHelper.refreshSponsorBlockSettings(requireContext());
                    InfoCache.getInstance().clearCache();
                }
            };

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        configureServerPreference(R.string.sponsor_block_youtube_api_url_key,
                ServiceList.YouTube.getServiceId());
        configureServerPreference(R.string.sponsor_block_bilibili_api_url_key,
                ServiceList.BiliBili.getServiceId());

        final Preference sponsorBlockWebsitePreference =
                findPreference(getString(R.string.sponsor_block_home_page_key));
        assert sponsorBlockWebsitePreference != null;
        sponsorBlockWebsitePreference.setOnPreferenceClickListener((Preference p) -> {
            final Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.sponsor_block_homepage_url)));
            startActivity(i);
            return true;
        });

        final Preference sponsorBlockPrivacyPreference =
                findPreference(getString(R.string.sponsor_block_privacy_key));
        assert sponsorBlockPrivacyPreference != null;
        sponsorBlockPrivacyPreference.setOnPreferenceClickListener((Preference p) -> {
            final Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.sponsor_block_privacy_policy_url)));
            startActivity(i);
            return true;
        });
    }

    private void configureServerPreference(final int key, final int serviceId) {
        final EditTextPreference preference = (EditTextPreference) requirePreference(key);
        preference.setSummaryProvider(item -> {
            final String normalized = SponsorBlockExtractorHelper
                    .normalizeApiUrl(preference.getText());
            return normalized == null ? getString(R.string.sponsor_block_api_url_default_summary,
                    SponsorBlockExtractorHelper.getDefaultApiUrl(serviceId)) : normalized;
        });
        preference.setOnBindEditTextListener(input -> {
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            input.setHint(R.string.sponsor_block_api_url_hint);
        });
        preference.setOnPreferenceChangeListener((item, value) -> {
            final String url = ((String) value).trim();
            if (url.isEmpty() || SponsorBlockExtractorHelper.normalizeApiUrl(url) != null) {
                return true;
            }
            Toast.makeText(requireContext(), R.string.sponsor_block_api_url_invalid,
                    Toast.LENGTH_LONG).show();
            return false;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        defaultPreferences.registerOnSharedPreferenceChangeListener(sponsorBlockChange);
    }

    @Override
    public void onStop() {
        defaultPreferences.unregisterOnSharedPreferenceChangeListener(sponsorBlockChange);
        super.onStop();
    }
}
