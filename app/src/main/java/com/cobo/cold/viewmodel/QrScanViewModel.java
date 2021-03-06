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

package com.cobo.cold.viewmodel;

import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.cobo.coinlib.exception.CoinNotFindException;
import com.cobo.coinlib.exception.InvalidTransactionException;
import com.cobo.coinlib.utils.Coins;
import com.cobo.cold.AppExecutors;
import com.cobo.cold.BuildConfig;
import com.cobo.cold.DataRepository;
import com.cobo.cold.MainApplication;
import com.cobo.cold.R;
import com.cobo.cold.callables.GetUuidCallable;
import com.cobo.cold.db.entity.AccountEntity;
import com.cobo.cold.db.entity.CoinEntity;
import com.cobo.cold.encryptioncore.utils.ByteFormatter;
import com.cobo.cold.protocol.ZipUtil;
import com.cobo.cold.protocol.parser.ProtoParser;
import com.cobo.cold.scan.ScannedData;
import com.cobo.cold.ui.fragment.main.QRCodeScanFragment;
import com.cobo.cold.update.utils.Digest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Base64;
import org.spongycastle.util.encoders.DecoderException;

import java.util.List;

import static com.cobo.cold.Utilities.IS_SETUP_VAULT;
import static com.cobo.cold.ui.fragment.main.TxConfirmFragment.KEY_TX_DATA;
import static com.cobo.cold.ui.fragment.setup.WebAuthResultFragment.WEB_AUTH_DATA;

public class QrScanViewModel extends AndroidViewModel {

    private static final String TAG = "Vault.Qrcode.QrScanViewModel";

    private final boolean isSetupVault;
    private QRCodeScanFragment fragment;
    private final DataRepository repository;

    private QrScanViewModel(@NonNull Application application, DataRepository repository, boolean isSetupVault) {
        super(application);
        this.isSetupVault = isSetupVault;
        this.repository = repository;
        repository.loadCoins();
    }

    public void handleDecode(QRCodeScanFragment owner, ScannedData[] res)
            throws InvalidTransactionException, JSONException, CoinNotFindException,
            UuidNotMatchException, UnknowQrCodeException {
        this.fragment = owner;
        String valueType = res[0].valueType;
        JSONObject object = parseToJson(res, valueType);
        if (object == null) {
            throw new JSONException("object null");
        }
        decodeAndProcess(object);
    }

    private void decodeAndProcess(JSONObject object)
            throws InvalidTransactionException,
            CoinNotFindException,
            JSONException,
            UuidNotMatchException,
            UnknowQrCodeException {
        logObject(object);

        String type = object.getString("type");
        switch (type) {
            case "webAuth":
                handleWebAuth(object);
                break;
            case "TYPE_SIGN_TX":
                handleSign(object);
                break;
            case "TYPE_SYNC":
                handleSync(object);
                break;
            default:
                throw new UnknowQrCodeException("unknow qrcode type " + type);
        }
    }

    private void logObject(JSONObject object) {
        if (!BuildConfig.DEBUG) {
            return;
        }

        try {
            Log.w(TAG, "object = " + object.toString(4));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void handleSync(JSONObject object) throws JSONException, UuidNotMatchException {
        checkUuid(object);
        JSONObject sync = object.getJSONObject("sync");
        JSONArray coins = sync.getJSONArray("coins");

        AppExecutors.getInstance().diskIO().execute(() -> {
            List<CoinEntity> coinEntities = repository.loadCoinsSync();
            for (int i = 0; i < coins.length(); i++) {
                try {
                    JSONObject coinObj = coins.getJSONObject(i);
                    CoinEntity coinEntity = matchCoinEntity(coinEntities, coinObj.getString("id"));
                    if (coinEntity == null) {
                        continue;
                    }
                    sync(coinObj, coinEntity);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            AppExecutors.getInstance().mainThread().execute(() -> fragment.navigateUp());
        });
    }

    private void sync(JSONObject coinObj, CoinEntity coinEntity) throws JSONException {
        List<AccountEntity> accountEntities = repository.loadAccountsForCoin(coinEntity);
        JSONArray accountObjs = coinObj.getJSONArray("accounts");
        for (int i = 0; i < accountObjs.length(); i++) {
            JSONObject accountObj = accountObjs.getJSONObject(i);
            AccountEntity accountEntity = matchAccountEntity(accountEntities, accountObj.getString("hdPath"));
            if (accountEntity == null) {
                continue;
            }
            int addressLengthNew = accountObj.getInt("addressLength");
            int addressLengthCurrent = accountEntity.getAddressLength();
            if (addressLengthNew > addressLengthCurrent) {
                String[] names = new String[addressLengthNew - addressLengthCurrent];
                int index = 0;
                for (int j = addressLengthCurrent; j < addressLengthNew; j++) {
                    names[index++] = coinEntity.getCoinCode() + "-" + (j + 1);
                }
                new AddAddressViewModel.AddAddressTask(coinEntity, repository, null).execute(names);
            }
        }
    }

    private AccountEntity matchAccountEntity(List<AccountEntity> accounts, String hdPath) {
        for (AccountEntity a : accounts) {
            if (a.getHdPath().equals(hdPath)) {
                return a;
            }
        }
        return null;
    }

    private CoinEntity matchCoinEntity(List<CoinEntity> coins, String code) {
        for (CoinEntity c : coins) {
            if (code.equals(c.getCoinCode()) || code.equals(c.getCoinId())) {
                return c;
            }
        }
        return null;
    }

    private void handleWebAuth(JSONObject object) throws JSONException {
        String data = object.getString("data");
        Bundle bundle = new Bundle();
        bundle.putString(WEB_AUTH_DATA, data);
        bundle.putBoolean(IS_SETUP_VAULT, isSetupVault);
        if (isSetupVault) {
            fragment.navigate(R.id.action_to_webAuthResultFragment, bundle);
        } else {
            fragment.navigate(R.id.action_QRCodeScan_to_result, bundle);
        }
    }

    private void handleSign(JSONObject object)
            throws InvalidTransactionException,
            CoinNotFindException,
            UuidNotMatchException {
        checkUuid(object);
        try {
            String coinCode = object.getJSONObject("signTx")
                    .getString("coinCode");

            if (!Coins.isCoinSupported(coinCode)) {
                throw new CoinNotFindException("not support " + coinCode);
            }
            Bundle bundle = new Bundle();
            bundle.putString(KEY_TX_DATA, object.getJSONObject("signTx").toString());
            fragment.navigate(R.id.action_to_txConfirmFragment, bundle);
        } catch (JSONException e) {
            throw new InvalidTransactionException("invalid transaction");
        }
    }

    private JSONObject parseToJson(ScannedData[] res, String valueType) {
        JSONObject object = null;

        if ("protobuf".equals(valueType)) {
            try {
                byte[] data = combineProtobufData(res);
                if (data != null) {
                    object = new ProtoParser(data).parseToJson();
                }
            } catch (DecoderException e) { }

        } else {
            String json = combineJsonData(res);
            try {
                object = new JSONObject(json).getJSONObject("data");
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return object;
    }

    private String combineJsonData(ScannedData[] scannedData) {
        StringBuilder sb = new StringBuilder();
        for (ScannedData data : scannedData) {
            sb.append(data.value);
        }

        if (scannedData[0].compress) {
            byte[] data = Base64.decode(sb.toString());
            data = ZipUtil.unzip(data);
            return new String(data != null ? data : new byte[0]);
        } else {
            return sb.toString();
        }
    }

    private byte[] combineProtobufData(ScannedData[] res) {
        StringBuilder sb = new StringBuilder();
        String checkSum = res[0].checkSum;
        for (ScannedData data : res) {
            if (!data.checkSum.equals(checkSum)) {
                return null;
            }
            sb.append(data.value);
        }

        String actualChecksum = ByteFormatter.bytes2hex(Digest.MD5.checksum(sb.toString()));
        if (!checkSum.equals(actualChecksum)) {
            return null;
        }


        byte[] data = Base64.decode(sb.toString());
        if (res[0].compress) {
            data = ZipUtil.unzip(data);
        }
        return data;
    }

    private void checkUuid(JSONObject obj) throws UuidNotMatchException {
        String uuid = new GetUuidCallable().call();
        if (!obj.optString("uuid").equals(uuid)) {
            throw new UuidNotMatchException("uuid not match");
        }
    }

    public static class Factory extends ViewModelProvider.NewInstanceFactory {
        @NonNull
        private final Application mApplication;

        private final boolean mIsSetupVault;

        private final DataRepository mRepository;

        public Factory(@NonNull Application application, boolean isSetupVault) {
            mApplication = application;
            mIsSetupVault = isSetupVault;
            mRepository = ((MainApplication) application).getRepository();
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            //noinspection unchecked
            return (T) new QrScanViewModel(mApplication, mRepository, mIsSetupVault);
        }
    }
}
