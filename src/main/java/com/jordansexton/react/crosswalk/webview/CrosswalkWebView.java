package com.jordansexton.react.crosswalk.webview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.ValueCallback;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.webview.events.TopMessageEvent;

import org.chromium.base.ThreadUtils;
import org.xwalk.core.JavascriptInterface;
import org.xwalk.core.XWalkGetBitmapCallback;
import org.xwalk.core.XWalkNavigationHistory;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import javax.annotation.Nullable;

public class CrosswalkWebView extends XWalkView implements LifecycleEventListener {
    private static CrosswalkWebView mInstanceActivity;
    public static CrosswalkWebView getmInstanceActivity() {
        return mInstanceActivity;
    }

    private EventDispatcher eventDispatcher;

    private final ResourceClient resourceClient;
    private final UIClient uiClient;

    private @Nullable String injectedJavaScript;

    private boolean isJavaScriptInjected;
    private boolean isChoosingFile;
    private boolean messagingEnabled = false;

    private final String BRIDGE_NAME = "__REACT_CROSSWALK_VIEW_BRIDGE";

    private class CrosswalkWebViewBridge {
        CrosswalkWebView mContext;

        CrosswalkWebViewBridge(CrosswalkWebView c) {
            mContext = c;
        }

        @JavascriptInterface
        public void postMessage(String message) {
            mContext.onMessage(message);
        }
    }

    public CrosswalkWebView (ReactContext reactContext, Activity _activity) {
        super(reactContext, _activity);

        eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        resourceClient = new ResourceClient(this);
        uiClient = new UIClient(this);

        this.setResourceClient(resourceClient);
        this.setUIClient(uiClient);
    }

    public CrosswalkWebView (Context context, AttributeSet attributes) {
        super(context, attributes);

        resourceClient = new ResourceClient(this);
        uiClient = new UIClient(this);

        mInstanceActivity = this;

        this.setResourceClient(resourceClient);
        this.setUIClient(uiClient);
    }

    public interface onPrintScreenLoadedCallback {
            void onSuccess(Bitmap surfaceBitmap);
            void onError();
    }

    public void captureXWalkBitmap(final onPrintScreenLoadedCallback callback) {
        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mInstanceActivity.captureBitmapAsync(new XWalkGetBitmapCallback() {
                    //Note: onFinishGetBitmap happens at the same thread as captureBitmapAsync, usually the UI thread.
                    @Override
                    public void onFinishGetBitmap(Bitmap bitmap, int response) {
                        Log.d("captureXWalkBitmap", "onFinishGetBitmap");

                        if (response == 0) {
                            callback.onSuccess(bitmap);

                        } else {
                            callback.onError();
                        }
                    }
                });
            }
        });
    }

    ReactContext reactContext;

    public void bindContext(ReactContext context) {
        reactContext = context;
        eventDispatcher = context.getNativeModule(UIManagerModule.class).getEventDispatcher();
    }

    public void unbindContext() {
        if(reactContext!=null) {
            reactContext.removeLifecycleEventListener(this);
            reactContext = null;
        }
    }

    public Boolean getLocalhost () {
        return resourceClient.getLocalhost();
    }

    public void setLocalhost (Boolean localhost) {
        resourceClient.setLocalhost(localhost);
    }

    @Override
    public void onHostResume() {
        resumeTimers();
        onShow();
    }

    @Override
    public void onHostPause() {
        pauseTimers();
        if (!isChoosingFile) {
            onHide();
        }
    }

    @Override
    public void onHostDestroy() {
        onDestroy();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (isChoosingFile) {
            isChoosingFile = false;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void load (String url, String content) {
        isJavaScriptInjected = false;
        isChoosingFile = false;
        super.load(url, content);
    }

    public void setInjectedJavaScript(@Nullable String js) {
        injectedJavaScript = js;
    }

    public void callInjectedJavaScript() {
        if (!isJavaScriptInjected) {
            isJavaScriptInjected = true;
        }

        if (injectedJavaScript != null && !TextUtils.isEmpty(injectedJavaScript)) {
            this.evaluateJavascript(injectedJavaScript, null);
        }
    }

    public void setMessagingEnabled(boolean enabled) {
        if (messagingEnabled == enabled) {
            return;
        }

        messagingEnabled = enabled;
        if (enabled) {
            addJavascriptInterface(new CrosswalkWebViewBridge(this), BRIDGE_NAME);
            linkBridge();
        } else {
            removeJavascriptInterface(BRIDGE_NAME);
        }
    }

    public void linkBridge() {
        if (messagingEnabled) {
            this.evaluateJavascript(
                "window.originalPostMessage = window.postMessage," +
                "window.postMessage = function(data) {" +
                BRIDGE_NAME + ".postMessage(String(data));" +
            "}", null);
        }
    }

    public void onMessage(String message) {
        eventDispatcher.dispatchEvent(new TopMessageEvent(this.getId(), message));
    }

    protected class ResourceClient extends XWalkResourceClient {

        private Boolean localhost = false;

        ResourceClient (XWalkView view) {
            super(view);
        }

        public Boolean getLocalhost () {
            return localhost;
        }

        public void setLocalhost (Boolean _localhost) {
            localhost = _localhost;
        }

        @Override
        public void onLoadFinished (XWalkView view, String url) {
           if(!url.equals("")){
               ((CrosswalkWebView) view).linkBridge();
               ((CrosswalkWebView) view).callInjectedJavaScript();

               XWalkNavigationHistory navigationHistory = view.getNavigationHistory();
               eventDispatcher.dispatchEvent(
                   new LoadFinishedEvent(
                       getId(),
                       SystemClock.uptimeMillis()
                   )
               );
               if(navigationHistory!=null)
               eventDispatcher.dispatchEvent(
                   new NavigationStateChangeEvent(
                       getId(),
                       SystemClock.uptimeMillis(),
                       view.getTitle(),
                       false,
                       url,
                       navigationHistory.canGoBack(),
                       navigationHistory.canGoForward()
                   )
               );
           }

        }

        @Override
        public void onLoadStarted (XWalkView view, String url) {
           if(!url.equals("")){
            XWalkNavigationHistory navigationHistory = view.getNavigationHistory();
            if(navigationHistory!=null)
            eventDispatcher.dispatchEvent(
                new NavigationStateChangeEvent(
                    getId(),
                    SystemClock.uptimeMillis(),
                    view.getTitle(),
                    true,
                    url,
                    navigationHistory.canGoBack(),
                    navigationHistory.canGoForward()
                )
            );
        }
        }

        @Override
        public void onReceivedLoadError (XWalkView view, int errorCode, String description, String failingUrl) {
            eventDispatcher.dispatchEvent(
                new ErrorEvent(
                    getId(),
                    SystemClock.uptimeMillis(),
                    errorCode,
                    description,
                    failingUrl
                )
            );
        }

        @Override
        public void onProgressChanged (XWalkView view, int progressInPercent) {
            eventDispatcher.dispatchEvent(
                new ProgressEvent(
                    getId(),
                    SystemClock.uptimeMillis(),
                    progressInPercent
                )
            );
        }

        @Override
        public boolean shouldOverrideUrlLoading (XWalkView view, String url) {
            Uri uri = Uri.parse(url);
            if (uri.getScheme().equals(CrosswalkWebViewManager.JSNavigationScheme)) {
                onLoadFinished(view, url);
                return true;
            }
            else if (getLocalhost()) {
                if (uri.getHost().equals("localhost")) {
                    return false;
                }
                else {
                    overrideUri(uri);
                    return true;
                }
            }
            else if (uri.getScheme().equals("http") || uri.getScheme().equals("https") || uri.getScheme().equals("file")) {
                return false;
            }
            else {
                overrideUri(uri);
                return true;
            }
        }

        private void overrideUri (Uri uri) {
           try {
               Intent action = new Intent(Intent.ACTION_VIEW, uri);
               action.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
               getContext().startActivity(action);
            } catch (Exception e) {
                e.printStackTrace();
           }
        }
    }

    protected class UIClient extends XWalkUIClient {
        public UIClient(XWalkView view) {
            super(view);
        }

        @Override
        public void openFileChooser (XWalkView view, ValueCallback<Uri> uploadFile, String acceptType, String capture) {
            isChoosingFile = true;
            super.openFileChooser(view, uploadFile, acceptType, capture);
        }
    }
}
