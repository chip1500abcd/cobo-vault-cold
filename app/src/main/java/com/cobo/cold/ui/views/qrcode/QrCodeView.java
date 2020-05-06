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

package com.cobo.cold.ui.views.qrcode;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.databinding.DataBindingUtil;

import com.cobo.cold.AppExecutors;
import com.cobo.cold.R;
import com.cobo.cold.databinding.QrcodeModalBinding;
import com.cobo.cold.ui.modal.FullScreenModal;

public class QrCodeView extends FrameLayout implements QrCodeHolder {

    private String data;
    private final Cache mCache = Cache.getInstance();
    private ProgressBar progressBar;
    private ImageView img;

    public QrCodeView(Context context) {
        this(context, null);
    }

    public QrCodeView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public void setData(String s) {
        data = s;
        showQrCode();
    }

    public void disableModal() {
        img.setOnClickListener(null);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        img = findViewById(R.id.img);
        img.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(data)) {
                showModal();
            }
        });
        progressBar = findViewById(R.id.progress);
    }

    private void showQrCode() {
        if (ViewCompat.isLaidOut(this)) {
            mCache.offer(data, QrCodeView.this);
        } else {
            getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    mCache.offer(data, QrCodeView.this);
                }
            });
        }
    }

    public void showModal() {
        FullScreenModal dialog = new FullScreenModal();
        QrcodeModalBinding binding = DataBindingUtil.inflate(LayoutInflater.from(getContext()),
                R.layout.qrcode_modal, null, false);
        dialog.setBinding(binding);
        binding.close.setOnClickListener(v -> dialog.dismiss());
        binding.qrcodeLayout.qrcode.setData(data);
        binding.qrcodeLayout.qrcode.disableModal();
        dialog.show(((AppCompatActivity) getContext()).getSupportFragmentManager(), "");
    }

    private void setImageBitmap(Bitmap bm) {
        AppExecutors.getInstance().mainThread().execute(() -> {
            progressBar.setVisibility(GONE);
            img.setVisibility(VISIBLE);
            img.setImageBitmap(bm);
        });
    }

    @Override
    public void onRender(Bitmap bm) {
        setImageBitmap(bm);
    }

    @Override
    public int getViewWidth() {
        return img.getWidth();
    }

    @Override
    public int getViewHeight() {
        return img.getHeight();
    }
}
