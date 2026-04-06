/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package net.mikaelzero.mojito.view.sketch.core.zoom;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import net.mikaelzero.mojito.view.sketch.core.viewfun.FunctionCallbackView;

class TapHelper {

    private static final int LONG_PRESS_TIMEOUT = 400;

    @NonNull
    private final ImageZoomer imageZoomer;
    @NonNull
    private final Handler mainHandler;
    private final float longPressSlop;

    private float downX;
    private float downY;
    private boolean isLongPressTriggered = false;
    private MotionEvent downEvent = null;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isLongPressTriggered && downEvent != null) {
                isLongPressTriggered = true;
                performLongPress(downEvent);
            }
        }
    };

    TapHelper(@NonNull Context appContext, @NonNull ImageZoomer imageZoomer) {
        this.imageZoomer = imageZoomer;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.longPressSlop = ViewConfiguration.get(appContext).getScaledTouchSlop() * 2f;
    }

    boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                isLongPressTriggered = false;
                if (downEvent != null) {
                    downEvent.recycle();
                }
                downEvent = MotionEvent.obtain(event);
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dx > longPressSlop || dy > longPressSlop) {
                    cancelLongPress();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                break;
        }
        return true;
    }

    private void cancelLongPress() {
        mainHandler.removeCallbacks(longPressRunnable);
    }

    private void performLongPress(@NonNull MotionEvent e) {
        ImageView imageView = imageZoomer.getImageView();
        ImageZoomer.OnViewLongPressListener longPressListener = imageZoomer.getOnViewLongPressListener();
        if (longPressListener != null) {
            longPressListener.onViewLongPress(imageView, e.getX(), e.getY());
            return;
        }

        if (imageView instanceof FunctionCallbackView) {
            FunctionCallbackView functionCallbackView = (FunctionCallbackView) imageView;
            View.OnLongClickListener longClickListener = functionCallbackView.getOnLongClickListener();
            if (longClickListener != null && functionCallbackView.isLongClickable()) {
                longClickListener.onLongClick(imageView);
            }
        }
    }
}
