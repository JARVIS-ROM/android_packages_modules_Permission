/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.permissioncontroller.permission.ui;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.CANCELED;
import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.DENIED;
import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.DENIED_DO_NOT_ASK_AGAIN;
import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_ALWAYS;
import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_FOREGROUND_ONLY;
import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.GRANTED_ONE_TIME;
import static com.android.permissioncontroller.permission.ui.GrantPermissionsViewHandler.LINKED_TO_SETTINGS;
import static com.android.permissioncontroller.permission.utils.Utils.getRequestMessage;

import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Process;
import android.text.Annotation;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;
import androidx.fragment.app.FragmentActivity;

import com.android.permissioncontroller.DeviceUtils;
import com.android.permissioncontroller.R;
import com.android.permissioncontroller.permission.ui.auto.GrantPermissionsAutoViewHandler;
import com.android.permissioncontroller.permission.ui.model.GrantPermissionsViewModel;
import com.android.permissioncontroller.permission.ui.model.GrantPermissionsViewModel.RequestInfo;
import com.android.permissioncontroller.permission.ui.model.GrantPermissionsViewModelFactory;
import com.android.permissioncontroller.permission.ui.wear.GrantPermissionsWearViewHandler;
import com.android.permissioncontroller.permission.utils.KotlinUtils;
import com.android.permissioncontroller.permission.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * An activity which displays runtime permission prompts on behalf of an app.
 */
public class GrantPermissionsActivity extends FragmentActivity
        implements GrantPermissionsViewHandler.ResultListener {

    private static final String LOG_TAG = "GrantPermissionsActivit";

    private static final String KEY_SESSION_ID = GrantPermissionsActivity.class.getName()
            + "_REQUEST_ID";
    public static final String ANNOTATION_ID = "link";

    public static final int NEXT_BUTTON = 11;
    public static final int ALLOW_BUTTON = 0;
    public static final int ALLOW_ALWAYS_BUTTON = 1;
    public static final int ALLOW_FOREGROUND_BUTTON = 2;
    public static final int DENY_BUTTON = 3;
    public static final int DENY_AND_DONT_ASK_AGAIN_BUTTON = 4;
    public static final int ALLOW_ONE_TIME_BUTTON = 5;
    public static final int NO_UPGRADE_BUTTON = 6;
    public static final int NO_UPGRADE_AND_DONT_ASK_AGAIN_BUTTON = 7;
    public static final int NO_UPGRADE_OT_BUTTON = 8; // one-time
    public static final int NO_UPGRADE_OT_AND_DONT_ASK_AGAIN_BUTTON = 9; // one-time
    public static final int LINK_TO_SETTINGS = 10;

    private static final int APP_PERMISSION_REQUEST_CODE = 1;

    /** Unique Id of a request */
    private long mSessionId;

    private String[] mRequestedPermissions;
    private boolean[] mButtonVisibilities;
    private List<RequestInfo> mRequestInfos = new ArrayList<>();
    private GrantPermissionsViewHandler mViewHandler;
    private GrantPermissionsViewModel mViewModel;
    private boolean mResultSet;
    /** Package that requested the permission grant */
    private String mCallingPackage;
    private int mTotalRequests = 0;
    private int mCurrentRequestIdx = 0;
    private boolean mViewSetUp;
    private float mOriginalDimAmount;
    private View mRootView;

    // TODO ntmyren: remove after b/166095244 is fixed
    private Long mStartTime;

    @Override
    public void onCreate(Bundle icicle) {
        mStartTime = System.currentTimeMillis();
        Log.i(LOG_TAG, "GrantPermissionsActivity onCreate");
        super.onCreate(icicle);

        if (icicle == null) {
            mSessionId = new Random().nextLong();
        } else {
            mSessionId = icicle.getLong(KEY_SESSION_ID);
        }

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        // Cache this as this can only read on onCreate, not later.
        mCallingPackage = getCallingPackage();
        if (mCallingPackage == null) {
            Log.e(LOG_TAG, "null callingPackageName. Please use \"RequestPermission\" to "
                    + "request permissions");
            setResultAndFinish();
            return;
        }

        setFinishOnTouchOutside(false);

        setTitle(R.string.permission_request_title);

        mRequestedPermissions = getIntent().getStringArrayExtra(
                PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (mRequestedPermissions == null || mRequestedPermissions.length == 0) {
            setResultAndFinish();
            return;
        }

        if (DeviceUtils.isTelevision(this)) {
            mViewHandler = new com.android.permissioncontroller.permission.ui.television
                    .GrantPermissionsViewHandlerImpl(this,
                    mCallingPackage).setResultListener(this);
        } else if (DeviceUtils.isWear(this)) {
            mViewHandler = new GrantPermissionsWearViewHandler(this).setResultListener(this);
        } else if (DeviceUtils.isAuto(this)) {
            mViewHandler = new GrantPermissionsAutoViewHandler(this, mCallingPackage)
                    .setResultListener(this);
        } else {
            mViewHandler = new com.android.permissioncontroller.permission.ui.handheld
                    .GrantPermissionsViewHandlerImpl(this, mCallingPackage,
                    Process.myUserHandle()).setResultListener(this);
        }

        GrantPermissionsViewModelFactory factory = new GrantPermissionsViewModelFactory(
                getApplication(), mCallingPackage, mRequestedPermissions, mSessionId, icicle);
        mViewModel = factory.create(GrantPermissionsViewModel.class);
        mViewModel.getRequestInfosLiveData().observe(this, this::onRequestInfoLoad);

        mRootView = mViewHandler.createView();
        mRootView.setVisibility(View.GONE);
        setContentView(mRootView);
        mOriginalDimAmount = getWindow().getAttributes().dimAmount;
        // Restore UI state after lifecycle events. This has to be before we show the first request,
        // as the UI behaves differently for updates and initial creations.
        if (icicle != null) {
            setUpView();
            mViewHandler.loadInstanceState(icicle);
        } else {
            // Do not show screen dim until data is loaded
            getWindow().setDimAmount(0f);
        }
    }

    private void setUpView() {
        if (mViewSetUp) {
            return;
        }

        mViewSetUp = true;

        Window window = getWindow();
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        layoutParams.dimAmount = mOriginalDimAmount;
        mViewHandler.updateWindowAttributes(layoutParams);
        window.setAttributes(layoutParams);
    }

    private void onRequestInfoLoad(List<RequestInfo> requests) {
        if (!mViewModel.getRequestInfosLiveData().isInitialized() || mResultSet) {
            return;
        } else if (requests == null) {
            finish();
            return;
        } else if (requests.isEmpty()) {
            setResultAndFinish();
            return;
        }

        setUpView();

        if (mRequestInfos == null) {
            mTotalRequests = requests.size();
        }
        mRequestInfos = requests;

        showNextRequest();
    }

    private void showNextRequest() {
        if (mRequestInfos == null || mRequestInfos.isEmpty()) {
            return;
        }

        RequestInfo info = mRequestInfos.get(0);

        if (info.getSendToSettingsImmediately()) {
            mViewModel.sendDirectlyToSettings(this, info.getGroupName());
            return;
        }

        CharSequence appLabel = KotlinUtils.INSTANCE.getPackageLabel(getApplication(),
                mCallingPackage, Process.myUserHandle());

        int messageId = 0;
        switch(info.getMessage()) {
            case FG_MESSAGE:
                messageId = Utils.getRequest(info.getGroupName());
                break;
            case BG_MESSAGE:
                messageId = Utils.getBackgroundRequest(info.getGroupName());
                break;
            case UPGRADE_MESSAGE:
                messageId = Utils.getUpgradeRequest(info.getGroupName());
        }

        CharSequence message = getRequestMessage(appLabel, mCallingPackage,
                info.getGroupName(), this, messageId);

        int detailMessageId = 0;
        switch(info.getDetailMessage()) {
            case FG_MESSAGE:
                detailMessageId = Utils.getRequestDetail(info.getGroupName());
                break;
            case BG_MESSAGE:
                detailMessageId = Utils.getBackgroundRequestDetail(info.getGroupName());
                break;
            case UPGRADE_MESSAGE:
                detailMessageId = Utils.getUpgradeRequestDetail(info.getGroupName());
        }

        Spanned detailMessage = null;
        if (detailMessageId != 0) {
            detailMessage =
                    new SpannableString(getText(detailMessageId));
            Annotation[] annotations = detailMessage.getSpans(
                    0, detailMessage.length(), Annotation.class);
            int numAnnotations = annotations.length;
            for (int i = 0; i < numAnnotations; i++) {
                Annotation annotation = annotations[i];
                if (annotation.getValue().equals(ANNOTATION_ID)) {
                    int start = detailMessage.getSpanStart(annotation);
                    int end = detailMessage.getSpanEnd(annotation);
                    ClickableSpan clickableSpan = getLinkToAppPermissions(info);
                    SpannableString spannableString =
                            new SpannableString(detailMessage);
                    spannableString.setSpan(clickableSpan, start, end, 0);
                    detailMessage = spannableString;
                    break;
                }
            }
        }

        Icon icon = null;
        try {
            icon = Icon.createWithResource(info.getGroupInfo().getPackageName(),
                    info.getGroupInfo().getIcon());
        } catch (Resources.NotFoundException e) {
            Log.e(LOG_TAG, "Cannot load icon for group" + info.getGroupName(), e);
        }

        boolean showingNewGroup = message == null || !message.equals(getTitle());
        setTitle(message);
        ArrayList<Integer> idxs = new ArrayList<>();
        mButtonVisibilities = new boolean[info.getButtonVisibilities().size()];
        for (int i = 0; i < info.getButtonVisibilities().size(); i++) {
            mButtonVisibilities[i] = info.getButtonVisibilities().get(i);
            if (mButtonVisibilities[i]) {
                idxs.add(i);
            }
        }
        mViewHandler.updateUi(info.getGroupName(), mTotalRequests, mCurrentRequestIdx, icon,
                message, detailMessage, mButtonVisibilities);
        if (showingNewGroup) {
            mCurrentRequestIdx++;
        }
        mRootView.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        View rootView = getWindow().getDecorView();
        if (rootView.getTop() != 0) {
            // We are animating the top view, need to compensate for that in motion events.
            ev.setLocation(ev.getX(), ev.getY() - rootView.getTop());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        mViewHandler.saveInstanceState(outState);
        mViewModel.saveInstanceState(outState);

        outState.putLong(KEY_SESSION_ID, mSessionId);
    }

    private ClickableSpan getLinkToAppPermissions(RequestInfo info) {
        return new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                logGrantPermissionActivityButtons(info.getGroupName(), LINKED_TO_SETTINGS);
                mViewModel.sendToSettingsFromLink(GrantPermissionsActivity.this,
                        info.getGroupName());
            }
        };
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Consumer<Intent> callback = mViewModel.getActivityResultCallback();

        if (requestCode == APP_PERMISSION_REQUEST_CODE && callback != null) {
            callback.accept(data);
            mViewModel.setActivityResultCallback(null);
        }
    }

    @Override
    public void onPermissionGrantResult(String name,
            @GrantPermissionsViewHandler.Result int result) {
        if (result == GRANTED_ALWAYS || result == GRANTED_FOREGROUND_ONLY
                || result == DENIED_DO_NOT_ASK_AGAIN) {
            KeyguardManager kgm = getSystemService(KeyguardManager.class);

            if (kgm.isDeviceLocked()) {
                kgm.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                            @Override
                            public void onDismissError() {
                                Log.e(LOG_TAG, "Cannot dismiss keyguard perm=" + name
                                        + " result=" + result);
                            }

                            @Override
                            public void onDismissCancelled() {
                                // do nothing (i.e. stay at the current permission group)
                            }

                            @Override
                            public void onDismissSucceeded() {
                                // Now the keyguard is dismissed, hence the device is not locked
                                // anymore
                                onPermissionGrantResult(name, result);
                            }
                        });

                return;
            }
        }

        logGrantPermissionActivityButtons(name, result);
        mViewModel.onPermissionGrantResult(name, result);
        showNextRequest();
        if (result == CANCELED) {
            setResultAndFinish();
        }
    }

    @Override
    public void onBackPressed() {
        mViewHandler.onBackPressed();
    }

    @Override
    public void finish() {
        setResultIfNeeded(RESULT_CANCELED);
        mViewModel.autoGrantNotify();
        Long visibleTime = System.currentTimeMillis() - mStartTime;
        mViewModel.logState("Finished in " + visibleTime + "ms");
        super.finish();
    }

    private void setResultIfNeeded(int resultCode) {
        if (!mResultSet) {
            mResultSet = true;
            mViewModel.logRequestedPermissionGroups();
            Intent result = new Intent(PackageManager.ACTION_REQUEST_PERMISSIONS);
            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, mRequestedPermissions);

            if (mViewModel.getRequestInfosLiveData().isInitialized()
                    && mViewModel.shouldReturnPermissionState()) {
                PackageManager pm = getPackageManager();
                int numRequestedPermissions = mRequestedPermissions.length;
                int[] grantResults = new int[numRequestedPermissions];
                for (int i = 0; i < numRequestedPermissions; i++) {
                    grantResults[i] = pm.checkPermission(mRequestedPermissions[i], mCallingPackage);
                }

                result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, grantResults);
            } else {
                result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, new int[0]);
            }
            setResult(resultCode, result);
        }
    }

    private void setResultAndFinish() {
        setResultIfNeeded(RESULT_OK);
        finish();
    }

    private void logGrantPermissionActivityButtons(String permissionGroupName, int grantResult) {
        int clickedButton = 0;
        int presentedButtons = getButtonState();
        switch (grantResult) {
            case GRANTED_ALWAYS:
                if (mButtonVisibilities[ALLOW_BUTTON]) {
                    clickedButton = 1 << ALLOW_BUTTON;
                } else {
                    clickedButton = 1 << ALLOW_ALWAYS_BUTTON;
                }
                break;
            case GRANTED_FOREGROUND_ONLY:
                clickedButton = 1 << ALLOW_FOREGROUND_BUTTON;
                break;
            case DENIED:
                if (mButtonVisibilities[NO_UPGRADE_BUTTON]) {
                    clickedButton = 1 << NO_UPGRADE_BUTTON;
                } else if (mButtonVisibilities[NO_UPGRADE_OT_BUTTON]) {
                    clickedButton = 1 << NO_UPGRADE_OT_BUTTON;
                } else if (mButtonVisibilities[DENY_BUTTON]) {
                    clickedButton = 1 << DENY_BUTTON;
                }
                break;
            case DENIED_DO_NOT_ASK_AGAIN:
                if (mButtonVisibilities[NO_UPGRADE_AND_DONT_ASK_AGAIN_BUTTON]) {
                    clickedButton = 1 << NO_UPGRADE_AND_DONT_ASK_AGAIN_BUTTON;
                } else if (mButtonVisibilities[NO_UPGRADE_OT_AND_DONT_ASK_AGAIN_BUTTON]) {
                    clickedButton = 1 << NO_UPGRADE_OT_AND_DONT_ASK_AGAIN_BUTTON;
                } else if (mButtonVisibilities[DENY_AND_DONT_ASK_AGAIN_BUTTON]) {
                    clickedButton = 1 << DENY_AND_DONT_ASK_AGAIN_BUTTON;
                }
                break;
            case GRANTED_ONE_TIME:
                clickedButton = 1 << ALLOW_ONE_TIME_BUTTON;
                break;
            case LINKED_TO_SETTINGS:
                clickedButton = 1 << LINK_TO_SETTINGS;
            case CANCELED:
                // fall through
            default:
                break;
        }

        mViewModel.logClickedButtons(permissionGroupName, clickedButton, presentedButtons);
    }

    private int getButtonState() {
        if (mButtonVisibilities == null) {
            return 0;
        }
        int buttonState = 0;
        for (int i = NEXT_BUTTON - 1; i >= 0; i--) {
            buttonState *= 2;
            if (mButtonVisibilities[i]) {
                buttonState++;
            }
        }
        return buttonState;
    }
}
