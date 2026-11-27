package io.github.unipus.helper;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Debug-only local browser fixture. It never starts Python or reads config. */
public final class BrowserKernelHarnessActivity extends Activity {
    public static final String FIXTURE_URL = "https://fixture.invalid/";
    private BrowserKernel kernel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout container = new FrameLayout(this);
        container.setFitsSystemWindows(true);
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(container);
        kernel = new BrowserKernel(this, container, new BrowserKernel.Listener() {
            @Override public void onPageState(String url, boolean loading) { }
            @Override public void onError(String message) { }
            @Override public void onTabsChanged() { }
        });
        kernel.getCurrentView().getSettings().setBlockNetworkLoads(true);
    }

    public BrowserKernel getKernel() { return kernel; }

    public void loadFixture(String html) {
        kernel.getCurrentView().getSettings().setBlockNetworkLoads(true);
        // A null historyUrl is exposed as about:blank by WebView.getUrl(),
        // even when the content has an HTTPS base URL and origin.
        kernel.getCurrentView().loadDataWithBaseURL(
                FIXTURE_URL, html, "text/html", "UTF-8", FIXTURE_URL);
    }

    @Override public void onDestroy() {
        if (kernel != null) kernel.destroy();
        super.onDestroy();
    }
}
