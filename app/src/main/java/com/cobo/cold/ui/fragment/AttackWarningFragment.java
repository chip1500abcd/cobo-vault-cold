/*
 * Copyright (c) 2020 Cobo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * in the file COPYING.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.cobo.cold.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemProperties;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.android.internal.app.LocalePicker;
import com.cobo.cold.R;
import com.cobo.cold.databinding.AttackWarningBinding;
import com.cobo.cold.ui.modal.ProgressModalDialog;
import com.cobo.cold.util.DataCleaner;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;

import static com.cobo.cold.ui.fragment.setting.MainPreferenceFragment.removeAllFingerprint;

public class AttackWarningFragment extends BaseFragment<AttackWarningBinding> {

    public static AttackWarningFragment newInstance(Bundle data){
        AttackWarningFragment fragment = new AttackWarningFragment();
        fragment.setArguments(data);
        return fragment;
    }

    @Override
    protected int setView() {
        return R.layout.attack_warning;
    }

    @Override
    protected void init(View view) {
        Bundle data = Objects.requireNonNull(getArguments());
        mBinding.hint.setText(getString(R.string.attack_warning,
                 formatErrorCode(data)));
        mBinding.powerOff.setOnClickListener(v -> handleAttack(mActivity));
        mBinding.serialno.setText(getString(R.string.serialno, SystemProperties.get("persist.sys.serialno")));
        mBinding.icon.setOnClickListener(new AboutFragment.ExportLogHandler(mActivity, Executors.newSingleThreadExecutor()));
    }


    public void handleAttack(AppCompatActivity activity) {
        ProgressModalDialog dialog = ProgressModalDialog.newInstance();
        dialog.show(Objects.requireNonNull(activity.getSupportFragmentManager()), "");
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                DataCleaner.cleanApplicationData(activity);
                removeAllFingerprint(activity);
                LocalePicker.updateLocale(Locale.SIMPLIFIED_CHINESE);
            } catch (Exception ignore){
            }finally {
                DataCleaner.cleanApplicationData(activity);
                powerOff();
            }
        });
    }

    private void powerOff() {
        Intent i = new Intent("com.android.internal.intent.action.REQUEST_SHUTDOWN");
        i.putExtra("android.intent.extra.KEY_CONFIRM", false);
        i.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mActivity.startActivity(i);
    }

    private String formatErrorCode(Bundle data) {
        int firmware = data.getInt("firmware");
        int system = data.getInt("system");
        int signature = data.getInt("signature");
        return "0x" + String.format("%08x", firmware << 16 | system << 8 | signature);
    }

    @Override
    protected void initData(Bundle savedInstanceState) {

    }
}
