/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't
 * displayed. On devices with low-bit ambient mode, the text is drawn without
 * anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

  public static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();

  private static final Typeface NORMAL_TYPEFACE =
    Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

  private static final Typeface BOLD_TYPEFACE =
    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

  /**
   * Update rate in milliseconds for interactive mode. We update once a second
   * since seconds are displayed in interactive mode.
   */
  private static final long INTERACTIVE_UPDATE_RATE_MS
    = TimeUnit.SECONDS.toMillis(1);

  /**
   * Handler message id for updating the time periodically in interactive mode.
   */
  private static final int MSG_UPDATE_TIME = 0;

  private int mWidth;
  private float mCenterX;

  @Override
  public Engine onCreateEngine() {
    return new Engine();
  }

  private static class EngineHandler extends Handler {
    private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

    public EngineHandler(SunshineWatchFace.Engine reference) {
      mWeakReference = new WeakReference<>(reference);
    }

    @Override
    public void handleMessage(Message msg) {
      SunshineWatchFace.Engine engine = mWeakReference.get();
      if (engine != null) {
        switch (msg.what) {
          case MSG_UPDATE_TIME:
            engine.handleUpdateTimeMessage();
            break;
        }
      }
    }
  }

  private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
    GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String COLON_STRING = ":";
    private static final float DIVIDER_MULTIPLIER = .4f;

    final Handler mUpdateTimeHandler = new EngineHandler(this);
    boolean mRegisteredTimeZoneReceiver = false;
    boolean mAmbient;

    Paint mBackgroundPaint;
    Paint mDividerPaint;
    Paint mHourPaint;
    Paint mMinutePaint;
    Paint mAmPmPaint;
    Paint mColonPaint;
    Paint mDateTextPaint;
    Paint mHighTempPaint;
    Paint mLowTempPaint;
    Paint mIconPaint;

    Calendar mCalendar;
    SimpleDateFormat mFormat;

    final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        mCalendar.setTimeZone(TimeZone.getDefault());
        invalidate();
      }
    };

    float mTimeOffsetX;
    float mTimeOffsetY;
    float mDateOffsetX;
    float mTempOffsetX;

    GoogleApiClient mGoogleApiClient;
    int mWeatherId;
    String mHighTemp;
    String mLowTemp;
    Bitmap mWeatherIcon;
    String mAmString;
    String mPmString;

    /**
     * Whether the display supports fewer bits for each color in ambient mode. When true, we
     * disable anti-aliasing in ambient mode.
     */
    boolean mLowBitAmbient;

    @Override
    public void onCreate(SurfaceHolder holder) {
      super.onCreate(holder);

      setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
        .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
        .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
        .setShowSystemUiTime(false)
        .build());
      Resources resources = SunshineWatchFace.this.getResources();

      mBackgroundPaint = new Paint();
      mBackgroundPaint.setColor(resources.getColor(R.color.primary));

      mDividerPaint = new Paint();
      mDividerPaint.setColor(resources.getColor(R.color.primary_light));

      mHourPaint = createTextPaint(resources.getColor(R.color.text));
      mColonPaint = createTextPaint(resources.getColor(R.color.primary_light));
      mMinutePaint = createTextPaint(resources.getColor(R.color.text));
      mAmPmPaint = createTextPaint(resources.getColor(R.color.primary_light));
      mHighTempPaint = createTextPaint(resources.getColor(R.color.text));
      mLowTempPaint = createTextPaint(resources.getColor(R.color.primary_light));
      mDateTextPaint = createTextPaint(resources.getColor(R.color.primary_light));

      mTimeOffsetY = resources.getDimension(R.dimen.y_offset);

      mCalendar = Calendar.getInstance();
      mFormat
        = new SimpleDateFormat(resources.getString(R.string.date_format), Locale.getDefault());

      mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
        .addApi(Wearable.API)
        .addConnectionCallbacks(this)
        .addOnConnectionFailedListener(this)
        .build();

      mAmString = resources.getString(R.string.am);
      mPmString = resources.getString(R.string.pm);

      mGoogleApiClient.connect();
    }

    @Override
    public void onDestroy() {
      Log.d(LOG_TAG, "onDestroy");
      mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
      super.onDestroy();
      Wearable.DataApi.removeListener(mGoogleApiClient, this);
      mGoogleApiClient.disconnect();
    }

    private Paint createTextPaint(int textColor) {
      Paint paint = new Paint();
      paint.setColor(textColor);
      paint.setTypeface(NORMAL_TYPEFACE);
      paint.setAntiAlias(true);
      return paint;
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);

      if (visible) {

        registerReceiver();

        // Update time zone in case it changed while we weren't visible.
        mCalendar.setTimeZone(TimeZone.getDefault());

      } else {
        unregisterReceiver();
      }

      // Whether the timer should be running depends on whether we're visible
      // (as well as whether we're in ambient mode), so we may need to start or
      // stop the timer.
      updateTimer();
    }

    private void registerReceiver() {
      if (mRegisteredTimeZoneReceiver) {
        return;
      }
      mRegisteredTimeZoneReceiver = true;
      IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
      SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
    }

    private void unregisterReceiver() {
      if (!mRegisteredTimeZoneReceiver) {
        return;
      }
      mRegisteredTimeZoneReceiver = false;
      SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
    }

    @Override
    public void onApplyWindowInsets(WindowInsets insets) {
      super.onApplyWindowInsets(insets);

      Log.d(LOG_TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));

      // Load resources that have alternate values for round watches.
      Resources resources = SunshineWatchFace.this.getResources();
      boolean isRound = insets.isRound();

      mTimeOffsetX = resources.getDimension(isRound
        ? R.dimen.time_offset_x_round : R.dimen.time_offset_x);

      mDateOffsetX = resources.getDimension(isRound
        ? R.dimen.date_offset_x_round : R.dimen.date_offset_x);

      mTempOffsetX = resources.getDimension(isRound
        ? R.dimen.temp_offset_x_round : R.dimen.temp_offset_x);

      mTimeOffsetY = resources.getDimension(isRound
        ? R.dimen.y_offset_round : R.dimen.y_offset);

      float timeTextSize = resources.getDimension(isRound
        ? R.dimen.time_text_size_round : R.dimen.time_text_size);

      float dateTextSize = resources.getDimension(isRound
        ? R.dimen.date_text_size_round : R.dimen.date_text_size);

      float tempTextSize = resources.getDimension(isRound
        ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);

      float meridianTextSize = resources.getDimensionPixelSize(isRound
        ? R.dimen.meridian_text_size_round : R.dimen.meridian_text_size);

      mHourPaint.setTextSize(timeTextSize);
      mColonPaint.setTextSize(timeTextSize);
      mMinutePaint.setTextSize(timeTextSize);
      mAmPmPaint.setTextSize(meridianTextSize);
      mDateTextPaint.setTextSize(dateTextSize);
      mHighTempPaint.setTextSize(tempTextSize);
      mLowTempPaint.setTextSize(tempTextSize);
    }

    @Override
    public void onPropertiesChanged(Bundle properties) {
      super.onPropertiesChanged(properties);
      mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

      boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
      mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
      mHighTempPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
    }

    @Override
    public void onTimeTick() {
      super.onTimeTick();
      invalidate();
    }

    @Override
    public void onAmbientModeChanged(boolean inAmbientMode) {
      Log.d(LOG_TAG, "onAmbientModeChanged");
      super.onAmbientModeChanged(inAmbientMode);
      Resources resources = SunshineWatchFace.this.getResources();

      if (inAmbientMode) {
        mColonPaint.setColor(resources.getColor(R.color.text));
        mDateTextPaint.setColor(resources.getColor(R.color.text));
        mDividerPaint.setColor(resources.getColor(R.color.text));
        mLowTempPaint.setColor(resources.getColor(R.color.text));
        mAmPmPaint.setColor(resources.getColor(R.color.text));
      } else {
        mColonPaint.setColor(resources.getColor(R.color.primary_light));
        mDateTextPaint.setColor(resources.getColor(R.color.primary_light));
        mDividerPaint.setColor(resources.getColor(R.color.primary_light));
        mLowTempPaint.setColor(resources.getColor(R.color.primary_light));
        mAmPmPaint.setColor(resources.getColor(R.color.primary_light));
      }

      if (mAmbient != inAmbientMode) {
        mAmbient = inAmbientMode;
        if (mLowBitAmbient) {
          mHourPaint.setAntiAlias(!inAmbientMode);
          mMinutePaint.setAntiAlias(!inAmbientMode);
          mColonPaint.setAntiAlias(!inAmbientMode);
          mDateTextPaint.setAntiAlias(!inAmbientMode);
        }

        invalidate();
      }

      // Whether the timer should be running depends on whether we're visible
      // (as well as whether we're in ambient mode), so we may need to start or
      // stop the timer.
      updateTimer();
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width,
                                 int height) {

      super.onSurfaceChanged(holder, format, width, height);

      mWidth = width;
      mCenterX = mWidth / 2f;
    }

    @Override
    public void onDraw(Canvas canvas, Rect bounds) {

      long now = System.currentTimeMillis();
      mCalendar.setTimeInMillis(now);
      Resources resources = SunshineWatchFace.this.getResources();
      boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);

      // Draw the background.
      if (isInAmbientMode()) {
        canvas.drawColor(Color.BLACK);
      } else {
        canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
      }

      float x = mTimeOffsetX;
      float y = mTimeOffsetY;
      String hourString;
      String timeFormat = resources.getString(R.string.time_format);
      if (is24Hour) {
        hourString
          = String.format(Locale.getDefault(), timeFormat, mCalendar.get(Calendar.HOUR_OF_DAY));
      } else {
        int hour = mCalendar.get(Calendar.HOUR);
        if (hour == 0) {
          hour = 12;
        }
        hourString = String.valueOf(hour);
      }

      canvas.drawText(hourString, x, y, mHourPaint);
      x += mHourPaint.measureText(hourString);

      // Draw the colon between hour and minute).
      canvas.drawText(COLON_STRING, x, y, mColonPaint);
      x += mColonPaint.measureText(COLON_STRING);

      // Minute
      String minute
        = String.format(Locale.getDefault(), timeFormat, mCalendar.get(Calendar.MINUTE));
      canvas.drawText(minute, x, y, mMinutePaint);
      x += mMinutePaint.measureText(minute);

      float marginRight = resources.getDimension(R.dimen.margin_right);
      // if we are in 12 hour mode, display meridian
      if (!is24Hour) {
        String meridian = mCalendar.get(Calendar.AM_PM) == Calendar.AM ? mAmString : mPmString;
        canvas.drawText(meridian, x + marginRight, y, mAmPmPaint);
      }

      // Date
      String dateText = mFormat.format(new Date());
      y += resources.getDimension(R.dimen.time_margin_bottom);
      canvas.drawText(dateText, mDateOffsetX, y, mDateTextPaint);

      if (mHighTemp != null && mLowTemp != null) {

        float lineLength = (mWidth * DIVIDER_MULTIPLIER) / 2;
        y += resources.getDimension(R.dimen.date_margin_bottom);
        canvas.drawLine(mCenterX - lineLength, y, mCenterX + lineLength, y, mDividerPaint);

        y += resources.getDimension(R.dimen.divider_margin_bottom);


        if (mWeatherIcon != null && !isInAmbientMode()) {
          float iconMargin = resources.getDimension(R.dimen.icon_margin_vertical);
          canvas.drawBitmap(mWeatherIcon, mTempOffsetX, y - iconMargin, mIconPaint);
        }

        float tempX = mCenterX;
        float highTempWidth = mHighTempPaint.measureText(mHighTemp);
        float textPadding =  highTempWidth / 2;
        if (mAmbient) {
          tempX -= textPadding;
        }
        canvas.drawText(mHighTemp, tempX - textPadding, y, mHighTempPaint);
        canvas.drawText(mLowTemp, tempX + textPadding + marginRight, y, mLowTempPaint);

      }

    }

    /**
     * Starts the {@link #mUpdateTimeHandler} timer if it should be running and
     * isn't currently or stops it if it shouldn't be running but currently is.
     */
    private void updateTimer() {
      mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
      if (shouldTimerBeRunning()) {
        mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
      }
    }

    /**
     * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
     * only run when we're visible and in interactive mode.
     */
    private boolean shouldTimerBeRunning() {
      return isVisible() && !isInAmbientMode();
    }

    /**
     * Handle updating the time periodically in interactive mode.
     */
    private void handleUpdateTimeMessage() {
      invalidate();
      if (shouldTimerBeRunning()) {
        long timeMs = System.currentTimeMillis();
        long delayMs = INTERACTIVE_UPDATE_RATE_MS
          - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
        mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
      }
    }

    /**
     * GoogleApiClient.ConnectionCallbacks
     *
     * @param connectionHint
     */
    @Override
    public void onConnected(Bundle connectionHint) {
      Log.d(LOG_TAG, "Connected to Google Play API");
      Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    /**
     * GoogleApiClient.ConnectionCallbacks
     *
     * @param cause
     */
    @Override
    public void onConnectionSuspended(int cause) {
      Log.d(LOG_TAG, "Google API conneciton suspended: " + cause);
      Wearable.DataApi.removeListener(mGoogleApiClient, this);
    }

    /**
     * GoogleApiClient.OnConnectionFailedListener
     */
    @Override
    public void onConnectionFailed(ConnectionResult result) {
      Log.d(LOG_TAG, "Google API connection failed: " + result.toString());
    }

    /**
     * DataApi.DataListener
     *
     * @param dataEvents
     */
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {

      Log.d(LOG_TAG, "onDataChanged");

      final String TOPIC = "/sunshine-weather";
      final String WEATHER_ID_KEY = "weatherId";
      final String HIGH_TEMP_KEY = "highTemp";
      final String LOW_TEMP_KEY = "lowTemp";
      final String WEATHER_ICON = "weatherIcon";

      for (DataEvent event : dataEvents) {
        if (event.getType() == DataEvent.TYPE_CHANGED) {
          // DataItem changed
          DataItem item = event.getDataItem();
          if (item.getUri().getPath().compareTo(TOPIC) == 0) {

            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            mWeatherId = dataMap.getInt(WEATHER_ID_KEY);
            mHighTemp = dataMap.getString(HIGH_TEMP_KEY);
            mLowTemp = dataMap.getString(LOW_TEMP_KEY);
            DownloadBitmapTask task = new DownloadBitmapTask();
            task.execute(dataMap.getAsset(WEATHER_ICON));
            Log.d(
              LOG_TAG,
              String.format("Data item changed: high: %s low: %s", mHighTemp, mLowTemp)
            );
          }
        }
      }
    }

    private class DownloadBitmapTask extends AsyncTask<Asset, Void, Bitmap> {

      @Override
      protected Bitmap doInBackground(Asset... params) {
        return loadBitmapFromAsset(params[0]);
      }

      @Override
      protected void onPostExecute(Bitmap bitmap) {

        int iconSize = (int) SunshineWatchFace.this.getResources().getDimension(R.dimen.icon_size);
        mWeatherIcon = Bitmap.createScaledBitmap(bitmap, iconSize, iconSize, false);
      }

      public Bitmap loadBitmapFromAsset(Asset asset) {
        if (asset == null) {
          throw new IllegalArgumentException("Asset must be non-null");
        }
        ConnectionResult result =
          mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
        if (!result.isSuccess()) {
          return null;
        }
        // convert asset into a file descriptor and block until it's ready
        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
          mGoogleApiClient, asset).await().getInputStream();
        mGoogleApiClient.disconnect();

        if (assetInputStream == null) {
          Log.w(LOG_TAG, "Requested an unknown Asset.");
          return null;
        }
        // decode the stream into a bitmap
        return BitmapFactory.decodeStream(assetInputStream);
      }
    }
  }
}
