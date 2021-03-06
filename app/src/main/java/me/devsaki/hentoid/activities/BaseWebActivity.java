package me.devsaki.hentoid.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Date;

import me.devsaki.hentoid.BuildConfig;
import me.devsaki.hentoid.HentoidApp;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.abstracts.BaseActivity;
import me.devsaki.hentoid.database.HentoidDB;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.parsers.ASMHentaiParser;
import me.devsaki.hentoid.parsers.HentaiCafeParser;
import me.devsaki.hentoid.parsers.HitomiParser;
import me.devsaki.hentoid.parsers.NhentaiParser;
import me.devsaki.hentoid.parsers.TsuminoParser;
import me.devsaki.hentoid.services.DownloadService;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.ConstsImport;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.LogHelper;
import me.devsaki.hentoid.views.ObservableWebView;

/**
 * Browser activity which allows the user to navigate a supported source.
 * No particular source should be filtered/defined here.
 * The source itself should contain every method it needs to function.
 */
public class BaseWebActivity extends BaseActivity {
    private static final String TAG = LogHelper.makeLogTag(BaseWebActivity.class);

    private Content currentContent;
    private HentoidDB db;
    private ObservableWebView webView;
    private Site site;
    private boolean webViewIsLoading;
    private FloatingActionButton fabRead, fabDownload, fabRefreshOrStop, fabHome;
    private boolean fabReadEnabled, fabDownloadEnabled;
    private SwipeRefreshLayout swipeLayout;

    Site getSite() {
        return site;
    }

    void setSite(Site site) {
        this.site = site;
    }

    ObservableWebView getWebView() {
        return webView;
    }

    void setWebView(ObservableWebView webView) {
        this.webView = webView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_base_web);

        db = HentoidDB.getInstance(this);

        setSite(getSite());
        if (site == null) {
            LogHelper.w(TAG, "Site is null!");
        } else {
            LogHelper.d(TAG, "Loading site: " + site);
        }

        fabRead = (FloatingActionButton) findViewById(R.id.fabRead);
        fabDownload = (FloatingActionButton) findViewById(R.id.fabDownload);
        fabRefreshOrStop = (FloatingActionButton) findViewById(R.id.fabRefreshStop);
        fabHome = (FloatingActionButton) findViewById(R.id.fabHome);

        hideFab(fabRead);
        hideFab(fabDownload);

        initWebView();
        initSwipeLayout();

        setWebView(getWebView());

        String intentVar = getIntent().getStringExtra(Consts.INTENT_URL);
        webView.loadUrl(intentVar == null ? site.getUrl() : intentVar);
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkPermissions();
    }

    // Validate permissions
    private void checkPermissions() {
        if (Helper.permissionsCheck(this, ConstsImport.RQST_STORAGE_PERMISSION, false)) {
            LogHelper.d(TAG, "Storage permission allowed!");
        } else {
            LogHelper.d(TAG, "Storage permission denied!");
            reset();
        }
    }

    private void reset() {
        Helper.reset(HentoidApp.getAppContext(), this);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        webView = (ObservableWebView) findViewById(R.id.wbMain);
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                if (result.getExtra().contains(site.getUrl())) {
                    backgroundRequest(result.getExtra());
                }
            } else {
                return true;
            }

            return false;
        });
        webView.setHapticFeedbackEnabled(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress == 100) {
                    swipeLayout.post(() -> swipeLayout.setRefreshing(false));
                } else {
                    swipeLayout.post(() -> swipeLayout.setRefreshing(true));
                }
            }
        });
        webView.setOnScrollChangedCallback((l, t) -> {
            if (!webViewIsLoading) {
                if (webView.canScrollVertically(1) || t == 0) {
                    fabRefreshOrStop.show();
                    fabHome.show();
                    if (fabReadEnabled) {
                        fabRead.show();
                    } else if (fabDownloadEnabled) {
                        fabDownload.show();
                    }
                } else {
                    fabRefreshOrStop.hide();
                    fabHome.hide();
                    fabRead.hide();
                    fabDownload.hide();
                }
            }
        });
        WebSettings webSettings = webView.getSettings();
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        String userAgent;
        try {
            userAgent = Helper.getAppUserAgent(this);
        } catch (PackageManager.NameNotFoundException e) {
            userAgent = Consts.USER_AGENT;
        }
        webSettings.setUserAgentString(userAgent);

        webSettings.setDomStorageEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
    }

    private void initSwipeLayout() {
        swipeLayout = (SwipeRefreshLayout) findViewById(R.id.swipe_container);
        swipeLayout.setOnRefreshListener(() -> {
            if (!swipeLayout.isRefreshing() || !webViewIsLoading) {
                webView.reload();
            }
        });
        swipeLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
    }

    @SuppressWarnings("UnusedParameters")
    public void onRefreshStopFabClick(View view) {
        if (webViewIsLoading) {
            webView.stopLoading();
        } else {
            webView.reload();
        }
    }

    private void goHome() {
        Intent intent = new Intent(this, DownloadsActivity.class);
        // If FLAG_ACTIVITY_CLEAR_TOP is not set,
        // it can interfere with Double-Back (press back twice) to exit
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (!getWebView().canGoBack()) {
            goHome();
        }
    }

    @SuppressWarnings("UnusedParameters")
    public void onHomeFabClick(View view) {
        goHome();
    }

    @SuppressWarnings("UnusedParameters")
    public void onReadFabClick(View view) {
        if (currentContent != null) {
            currentContent = db.selectContentById(currentContent.getId());
            if (StatusContent.DOWNLOADED == currentContent.getStatus()
                    || StatusContent.ERROR == currentContent.getStatus()) {
                FileHelper.openContent(this, currentContent);
            } else {
                hideFab(fabRead);
            }
        }
    }

    @SuppressWarnings("UnusedParameters")
    public void onDownloadFabClick(View view) {
        processDownload();
    }

    void processDownload() {
        currentContent = db.selectContentById(currentContent.getId());
        if (StatusContent.DOWNLOADED == currentContent.getStatus()) {
            Helper.toast(this, R.string.already_downloaded);
            hideFab(fabDownload);

            return;
        }
        Helper.toast(this, R.string.add_to_queue);
        currentContent.setDownloadDate(new Date().getTime())
                .setStatus(StatusContent.DOWNLOADING);

        db.updateContentStatus(currentContent);
        Intent intent = new Intent(Intent.ACTION_SYNC, null, this, DownloadService.class);

        startService(intent);
        hideFab(fabDownload);
    }

    private void hideFab(FloatingActionButton fab) {
        fab.hide();
        if (fab.equals(fabDownload)) {
            fabDownloadEnabled = false;
        } else if (fab.equals(fabRead)) {
            fabReadEnabled = false;
        }
    }

    private void showFab(FloatingActionButton fab) {
        fab.show();
        if (fab.equals(fabDownload)) {
            fabDownloadEnabled = true;
        } else if (fab.equals(fabRead)) {
            fabReadEnabled = true;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            WebBackForwardList webBFL = webView.copyBackForwardList();
            int i = webBFL.getCurrentIndex();
            do {
                i--;
            }
            while (i >= 0 && webView.getOriginalUrl()
                    .equals(webBFL.getItemAtIndex(i).getOriginalUrl()));
            if (webView.canGoBackOrForward(i - webBFL.getCurrentIndex())) {
                webView.goBackOrForward(i - webBFL.getCurrentIndex());
            } else {
                super.onBackPressed();
            }

            return true;
        }

        return false;
    }

    void processContent(Content content) {
        if (content == null) {
            return;
        }

        addContentToDB(content);

        StatusContent contentStatus = content.getStatus();
        if (contentStatus != StatusContent.DOWNLOADED
                && contentStatus != StatusContent.DOWNLOADING) {
            currentContent = content;
            runOnUiThread(() -> showFab(fabDownload));
        } else {
            runOnUiThread(() -> hideFab(fabDownload));
        }
        if (contentStatus == StatusContent.DOWNLOADED
                || contentStatus == StatusContent.ERROR) {
            currentContent = content;
            runOnUiThread(() -> showFab(fabRead));
        } else {
            runOnUiThread(() -> hideFab(fabRead));
        }

        // Allows debugging parsers without starting a content download
        if (BuildConfig.DEBUG) {
            attachToDebugger(content);
        }
    }

    private void addContentToDB(Content content) {
        Content contentDB = db.selectContentById(content.getUrl().hashCode());
        if (contentDB != null) {
            content.setStatus(contentDB.getStatus())
                    .setImageFiles(contentDB.getImageFiles())
                    .setDownloadDate(contentDB.getDownloadDate());
        }
        db.insertContent(content);
    }

    private void attachToDebugger(Content content) {
        switch (content.getSite()) {
            case HITOMI:
                HitomiParser.parseImageList(content);
                break;
            case NHENTAI:
                NhentaiParser.parseImageList(content);
                break;
            case TSUMINO:
                TsuminoParser.parseImageList(content);
                break;
            case ASMHENTAI:
                ASMHentaiParser.parseImageList(content);
                break;
            case HENTAICAFE:
                HentaiCafeParser.parseImageList(content);
                break;
            default:
                break;
        }
    }

    void backgroundRequest(String extra) {
        LogHelper.d(TAG, "Extras: " + extra);
    }

    class CustomWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            webViewIsLoading = true;
            fabRefreshOrStop.setImageResource(R.drawable.ic_action_clear);
            fabRefreshOrStop.show();
            fabHome.show();
            hideFab(fabDownload);
            hideFab(fabRead);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            webViewIsLoading = false;
            fabRefreshOrStop.setImageResource(R.drawable.ic_action_refresh);
        }
    }
}
