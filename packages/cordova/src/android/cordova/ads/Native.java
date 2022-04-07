package admob.plus.cordova.ads;

import android.graphics.Color;
import android.graphics.Rect;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import admob.plus.cordova.ExecuteContext;
import admob.plus.cordova.Generated.Events;
import admob.plus.core.Context;

import static admob.plus.core.Helper.dpToPx;
import static admob.plus.core.Helper.pxToDp;

public class Native extends AdBase {
    public static final String VIEW_DEFAULT_KEY = "default";
    public static final Map<String, ViewProvider> providers = new HashMap<String, ViewProvider>();

    private final AdRequest mAdRequest;
    private final ViewProvider viewProvider;
    private AdLoader mLoader;
    private NativeAd mAd;
    private View view;
    private View background;

    public Native(ExecuteContext ctx) {
        super(ctx);

        mAdRequest = ctx.optAdRequest();
        String key = ctx.optString("view");
        if (key == null || "".equals(key)) {
            key = VIEW_DEFAULT_KEY;
        }
        viewProvider = providers.get(key);
        if (viewProvider == null) {
            throw new RuntimeException("cannot find viewProvider: " + key);
        }
    }

    @Override
    public void onDestroy() {
        clear();

        super.onDestroy();
    }

    @Override
    public boolean isLoaded() {
        return mLoader != null && !mLoader.isLoading();
    }

    @Override
    public void load(Context ctx) {
        clear();

        // add background layer for background color
        if (background == null) {
            LayoutInflater inflater = LayoutInflater.from(getContentView().getContext());
            background = new View(inflater.getContext());
            background.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Objects.requireNonNull(getContentView()).addView(background, 0);
        }

        mLoader = new AdLoader.Builder(getActivity(), adUnitId)
                .forNativeAd(nativeAd -> {
                    mAd = nativeAd;
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(LoadAdError adError) {
                        emit(Events.AD_LOAD_FAIL, adError);
                        if (isLoaded()) {
                            ctx.reject(adError.toString());
                        }
                    }

                    public void onAdClosed() {
                        emit(Events.AD_DISMISS);
                    }

                    public void onAdOpened() {
                        emit(Events.AD_SHOW);
                    }

                    public void onAdLoaded() {
                        emit(Events.AD_LOAD);
                        if (isLoaded()) {
                            ctx.resolve();
                        }
                    }

                    public void onAdClicked() {
                        emit(Events.AD_CLICK);
                    }

                    public void onAdImpression() {
                        emit(Events.AD_IMPRESSION);
                    }
                })
                .build();
        mLoader.loadAd(mAdRequest);
    }

    @Override
    public void show(Context ctx) {
        if (view == null) {
            Integer layer = ctx.optInt("layer");
            int index = layer == null ? 1 : layer;
            view = viewProvider.createView(mAd);
            // setting index to 0 so the ad layer goes behind the web view
            Objects.requireNonNull(getContentView()).addView(view, layer);
        }
        // setting background of the layer behind the ad layer (hack to have background for app)
        if (background != null) {
            String bgColor = ctx.optString("bgColor");
            background.setBackgroundColor(bgColor != null ? Color.parseColor(bgColor) : Color.TRANSPARENT);
        }

        view.setVisibility(View.VISIBLE);
        view.setX((float) dpToPx(ctx.optDouble("x", 0.0)));
        view.setY((float) dpToPx(ctx.optDouble("y", 0.0)));
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = (int) dpToPx(ctx.optDouble("width", 0.0));
        params.height = (int) dpToPx(ctx.optDouble("height", 0.0));
        view.setLayoutParams(params);

        viewProvider.didShow(this);

        view.requestLayout();
        ctx.resolve(true);
    }

    @Override
    public void hide(Context ctx) {
        if (view != null) {
            view.setVisibility(View.GONE);
        }

        viewProvider.didHide(this);
        ctx.resolve();
    }

    @Override
    public void clickThrough(Context ctx) {
        double x = dpToPx((int) ctx.optDouble("x", 0.0));
        double y = dpToPx((int) ctx.optDouble("y", 0.0));
        double statusBarHeight = getStatusBarHeight();

        View foundView = findViewAt(getContentView(), (int) x, (int) (y + statusBarHeight));
        if (foundView != null) {
            foundView.callOnClick();
        }

        ctx.resolve();
    }

    private int getStatusBarHeight() {
        Rect rectangle = new Rect();
        Window window = getActivity().getWindow();
        window.getDecorView().getWindowVisibleDisplayFrame(rectangle);
        return rectangle.top;
    }

    private void clear() {
        if (mAd != null) {
            mAd.destroy();
            mAd = null;
        }
        if (view != null) {
            if (view instanceof NativeAdView) {
                NativeAdView v = (NativeAdView) view;
                Objects.requireNonNull(getContentView()).removeView(v);
                v.removeAllViews();
                v.destroy();
            }
            view = null;
        }
        mLoader = null;
    }

    private View findViewAt(ViewGroup viewGroup, int x, int y) {
        // since the media view is not part of the children, check it separately
        if (viewGroup instanceof NativeAdView) {
            NativeAdView nadView = (NativeAdView) viewGroup;
            MediaView mediaView = nadView.getMediaView();
            if (isClickInRect(mediaView, x, y)) {
                return mediaView;
            }
        }

        // check children
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                View foundView = findViewAt((ViewGroup) child, x, y);
                if (foundView != null && foundView.isShown()) {
                    return foundView;
                }
            } else {
                if (isClickInRect(child, x, y)) {
                    return child;
                }
            }
        }

        return null;
    }

    private boolean isClickInRect(View v, int x, int y) {
        int[] location = new int[2];
        v.getLocationOnScreen(location);
        Rect rect = new Rect(location[0], location[1], location[0] + v.getWidth(), location[1] + v.getHeight());
        return rect.contains(x, y);
    }

    public interface ViewProvider {
        @NonNull
        View createView(NativeAd nativeAd);

        default void didShow(@NonNull Native ad) {
        }

        default void didHide(@NonNull Native ad) {
        }
    }
}
