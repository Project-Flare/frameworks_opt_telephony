/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.telephony;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Telephony;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

public class WapPushOverSmsTest extends TelephonyTest {
    // Mocked classes
    protected ISms.Stub mISmsStub;

    private WapPushOverSms mWapPushOverSmsUT;

    @Mock private FeatureFlags mFeatureFlags;
    private static final UserHandle MOCKED_MAIN_USER = UserHandle.of(10);

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mISmsStub = mock(ISms.Stub.class);

        // Note that this replaces only cached services in ServiceManager. If a service is not found
        // in the cache, a real instance is used.
        mServiceManagerMockedServices.put("isms", mISmsStub);
        mFeatureFlags = Mockito.mock(FeatureFlags.class);
        doReturn(true).when(mFeatureFlags).smsMmsDeliverBroadcastsRedirectToMainUser();
        doReturn(mISmsStub).when(mISmsStub).queryLocalInterface(anyString());

        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        doReturn(MOCKED_MAIN_USER).when(userManager).getMainUser();

        mWapPushOverSmsUT = new WapPushOverSms(mContext, mFeatureFlags);
    }

    @After
    public void tearDown() throws Exception {
        WapPushCache.clear();
        mWapPushOverSmsUT = null;
        super.tearDown();
    }

    @Test @SmallTest
    public void testDispatchWapPdu() {
        doReturn(true).when(mWspTypeDecoder).decodeUintvarInteger(anyInt());
        doReturn(true).when(mWspTypeDecoder).decodeContentType(anyInt());
        doReturn((long)2).when(mWspTypeDecoder).getValue32();
        doReturn(2).when(mWspTypeDecoder).getDecodedDataLength();
        doReturn(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO).when(mWspTypeDecoder).getValueString();
        byte[] pdu = new byte[]{
                (byte) 0xFF,
                (byte) 0x06,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xFF
        };

        long messageId = 9989L;
        mWapPushOverSmsUT.dispatchWapPdu(pdu, null, mInboundSmsHandler, "123456", 0, messageId);

        ArgumentCaptor<Intent> intentArgumentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mInboundSmsHandler).dispatchIntent(intentArgumentCaptor.capture(),
                eq(android.Manifest.permission.RECEIVE_WAP_PUSH),
                eq(AppOpsManager.OPSTR_RECEIVE_WAP_PUSH),
                nullable(Bundle.class),
                isNull(InboundSmsHandler.SmsBroadcastReceiver.class),
                eq(MOCKED_MAIN_USER),
                anyInt());
        Intent intent = intentArgumentCaptor.getValue();
        assertEquals(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION, intent.getAction());
        assertEquals(0xFF, intent.getIntExtra("transactionId", 0));
        assertEquals(0x06, intent.getIntExtra("pduType", 0));
        assertEquals("123456", intent.getStringExtra("address"));
        assertEquals(messageId, intent.getLongExtra("messageId", 0L));

        byte[] header = intent.getByteArrayExtra("header");
        assertEquals(2, header.length);
        for (int i = 0; i < header.length; i++) {
            assertEquals((byte)0xFF, header[i]);
        }

        byte[] data = intent.getByteArrayExtra("data");
        assertEquals(pdu.length, data.length);
        for (int i = 0; i < pdu.length; i++) {
            assertEquals(pdu[i], data[i]);
        }

        assertEquals(mWspTypeDecoder.getContentParameters(),
                intent.getSerializableExtra("contentTypeParameters"));
    }

    @Test @SmallTest
    public void testDispatchWapPduFromBlockedNumber_noIntentsDispatched() throws Exception {
        when(mISmsStub.getCarrierConfigValuesForSubscriber(anyInt())).thenReturn(new Bundle());

        mFakeBlockedNumberContentProvider.mBlockedNumbers.add("16178269168");

        doReturn(true).when(mWspTypeDecoder).decodeUintvarInteger(anyInt());
        doReturn(true).when(mWspTypeDecoder).decodeContentType(anyInt());
        doReturn((long) 2).when(mWspTypeDecoder).getValue32();
        doReturn(2).when(mWspTypeDecoder).getDecodedDataLength();
        doReturn(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO).when(mWspTypeDecoder).getValueString();

        byte pdu[] = {1, 6, 0, 97, 112, 112, 108, 105, 99, 97, 116, 105, 111, 110, 47,
                118, 110, 100, 46, 119, 97, 112, 46, 109, 109, 115, 45, 109, 101, 115, 115,
                97, 103, 101, 0, -116, -126, -104, 77, 109, 115, 84, 114, 97, 110, 115, 97,
                99, 116, 105, 111, 110, 73, 68, 0, -115, 18, -119, 8, -128, 49, 54, 49, 55,
                56, 50, 54, 57, 49, 54, 56, 47, 84, 89, 80, 69, 61, 80, 76, 77, 78, 0, -118,
                -128, -114, 2, 3, -24, -120, 3, -127, 3, 3, -12, -128, -106, 84, 101, 115,
                116, 32, 77, 109, 115, 32, 83, 117, 98, 106, 101, 99, 116, 0, -125, 104, 116,
                116, 112, 58, 47, 47, 119, 119, 119, 46, 103, 111, 111, 103, 108, 101, 46, 99,
                111, 109, 47, 115, 97, 100, 102, 100, 100, 0};

        assertEquals(Telephony.Sms.Intents.RESULT_SMS_HANDLED,
                mWapPushOverSmsUT.dispatchWapPdu(pdu, null, mInboundSmsHandler, null, 0, 0L));
        verify(mInboundSmsHandler, never()).dispatchIntent(
                any(Intent.class),
                any(String.class),
                any(String.class),
                any(Bundle.class),
                any(InboundSmsHandler.SmsBroadcastReceiver.class),
                any(UserHandle.class),
                anyInt());
    }

    @Test @SmallTest
    public void testDispatchWapPdu_notificationIndInsertedToCache() throws Exception {
        assertEquals(0, WapPushCache.size());
        when(mISmsStub.getCarrierConfigValuesForSubscriber(anyInt())).thenReturn(new Bundle());

        doReturn(true).when(mWspTypeDecoder).decodeUintvarInteger(anyInt());
        doReturn(true).when(mWspTypeDecoder).decodeContentType(anyInt());
        doReturn((long) 2).when(mWspTypeDecoder).getValue32();
        doReturn(2).when(mWspTypeDecoder).getDecodedDataLength();
        doReturn(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO).when(mWspTypeDecoder).getValueString();

        byte[] pdu = {1, 6, 0, 97, 112, 112, 108, 105, 99, 97, 116, 105, 111, 110, 47,
                118, 110, 100, 46, 119, 97, 112, 46, 109, 109, 115, 45, 109, 101, 115, 115,
                97, 103, 101, 0, -116, -126, -104, 77, 109, 115, 84, 114, 97, 110, 115, 97,
                99, 116, 105, 111, 110, 73, 68, 0, -115, 18, -119, 8, -128, 49, 54, 49, 55,
                56, 50, 54, 57, 49, 54, 56, 47, 84, 89, 80, 69, 61, 80, 76, 77, 78, 0, -118,
                -128, -114, 2, 3, -24, -120, 3, -127, 3, 3, -12, -128, -106, 84, 101, 115,
                116, 32, 77, 109, 115, 32, 83, 117, 98, 106, 101, 99, 116, 0, -125, 104, 116,
                116, 112, 58, 47, 47, 119, 119, 119, 46, 103, 111, 111, 103, 108, 101, 46, 99,
                111, 109, 47, 115, 97, 100, 102, 100, 100, 0};

        mWapPushOverSmsUT.dispatchWapPdu(pdu, null, mInboundSmsHandler, null, 0, 0L);
        assertEquals(2, WapPushCache.size());
    }
}
