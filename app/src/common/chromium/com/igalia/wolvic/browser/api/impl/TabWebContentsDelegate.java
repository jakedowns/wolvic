package com.igalia.wolvic.browser.api.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.igalia.wolvic.browser.api.WAllowOrDeny;
import com.igalia.wolvic.browser.api.WResult;
import com.igalia.wolvic.browser.api.WSession;

import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.blink.mojom.DisplayMode;
import org.chromium.content_public.browser.InvalidateTypes;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;
import org.chromium.wolvic.WolvicWebContentsDelegate;

public class TabWebContentsDelegate extends WolvicWebContentsDelegate {
    private @NonNull SessionImpl mSession;
    private @NonNull final WebContents mWebContents;

    private boolean mIsFullscreen;

    public TabWebContentsDelegate(@NonNull SessionImpl session, WebContents webContents) {
        mSession = session;
        mWebContents = webContents;
    }

   @Override
   public boolean takeFocus(boolean reverse) {
       @Nullable WSession.ContentDelegate delegate = mSession.getContentDelegate();
       if (delegate != null && !reverse) {
           delegate.onFocusRequest(mSession);
           return true;
       }
       return false;
   }

    @Override
    public void enterFullscreenModeForTab(boolean prefersNavigationBar, boolean prefersStatusBar) {
        @Nullable WSession.ContentDelegate delegate = mSession.getContentDelegate();
        if (delegate == null) return;

        mIsFullscreen = true;
        delegate.onFullScreen(mSession, true);
    }

    @Override
    public void exitFullscreenModeForTab() {
        @Nullable WSession.ContentDelegate delegate = mSession.getContentDelegate();
        if (delegate == null) return;

        mIsFullscreen = false;
        delegate.onFullScreen(mSession, false);
    }

    @Override
    public boolean isFullscreenForTabOrPending() {
        return mIsFullscreen;
    }

    @Override
    public int getDisplayMode() {
        return !mIsFullscreen ? DisplayMode.BROWSER : DisplayMode.FULLSCREEN;
    }

    @Override
    public void navigationStateChanged(int flags) {
        if ((flags & InvalidateTypes.TITLE) != 0) {
            WSession.ContentDelegate delegate = mSession.getContentDelegate();
            if (delegate != null) {
                delegate.onTitleChange(mSession, mWebContents.getTitle());
            }
        }
    }

    public class OnNewSessionCallback implements WSession.NavigationDelegate.OnNewSessionCallback {
        public OnNewSessionCallback(RuntimeImpl runtime, GURL url) {
            mRuntime = runtime;
            mURL = url;
        }

        @Override
        public void onNewSession(WSession session) {
            ((SessionImpl) session).invokeOnReady(mRuntime, mURL.getSpec());
        }

        private RuntimeImpl mRuntime;
        private GURL mURL;
    }

    @Override
    public void onCreateNewWindow(GURL url) {
        WSession.NavigationDelegate delegate = mSession.getNavigationDelegate();
        assert delegate != null;

        // We must return before executing this, otherwise we might end up messing around the native
        // objects that chromium uses to back up some of the Java objects we use. For example the
        // WebContentsDelegate created by Chromium might be freed by the onNewSession() call when it
        // sets the new WebContentsDelegate object created by Wolvic.
        PostTask.postDelayedTask(TaskTraits.UI_DEFAULT, () -> {
            delegate.onNewSession(mSession, url.getSpec(), new OnNewSessionCallback(mSession.mRuntime, url));
        }, 0);
    }

    @Override
    public void onUpdateUrl(GURL url) {
        String newUrl = YoutubeUrlHelper.maybeRewriteYoutubeURL(url);
        // If mobile Youtube URL is detected, redirect to the desktop version.
        if (!url.getSpec().equals(newUrl)) {
            LoadUrlParams params = new LoadUrlParams(newUrl);
            mWebContents.getNavigationController().loadUrl(params);
            return;
        }

        WSession.NavigationDelegate delegate = mSession.getNavigationDelegate();
        if (delegate != null) {
            delegate.onLocationChange(mSession, mWebContents.getVisibleUrl().getSpec());
        }
    }

    @Override
    public void showRepostFormWarningDialog() {
        mSession.getChromiumPromptDelegate().onRepostConfirmWarningDialog().then(result -> {
            if (result.allowOrDeny() == WAllowOrDeny.ALLOW) {
                mWebContents.getNavigationController().continuePendingReload();
            } else {
                mWebContents.getNavigationController().cancelPendingReload();
            }
            return WResult.fromValue(null);
        });
    }
}
