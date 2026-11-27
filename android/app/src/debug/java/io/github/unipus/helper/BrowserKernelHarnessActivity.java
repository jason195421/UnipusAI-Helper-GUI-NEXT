package io.github.unipus.helper;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Debug-only local browser fixture. It never starts Python or reads config. */
public final class BrowserKernelHarnessActivity extends Activity {
    private BrowserKernel kernel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout container = new FrameLayout(this);
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
        kernel.getCurrentView().loadDataWithBaseURL(
                "https://fixture.invalid/", html, "text/html", "UTF-8", null);
    }

    @Override public void onDestroy() {
        if (kernel != null) kernel.destroy();
        super.onDestroy();
    }
}
