/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.credentials;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.slice.Slice;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.service.credentials.CredentialEntry;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public class CredentialManagerTest {
    @Mock
    private ICredentialManager mMockCredentialManagerService;

    @Mock
    private Activity mMockActivity;

    private GetCredentialRequest mGetRequest;
    private CreateCredentialRequest mCreateRequest;

    private ClearCredentialStateRequest mClearRequest;
    private RegisterCredentialDescriptionRequest mRegisterRequest;
    private UnregisterCredentialDescriptionRequest mUnregisterRequest;

    private CredentialManager mCredentialManager;
    private Executor mExecutor;
    private String mPackageName;

    private static boolean bundleEquals(Bundle a, Bundle b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        for (String aKey : a.keySet()) {
            if (!Objects.equals(a.get(aKey), b.get(aKey))) {
                return false;
            }
        }

        for (String bKey : b.keySet()) {
            if (!Objects.equals(b.get(bKey), a.get(bKey))) {
                return false;
            }
        }

        return true;
    }

    private static void assertBundleEquals(Bundle a, Bundle b) {
        assertThat(bundleEquals(a, b)).isTrue();
    }

    @Before
    public void setup() {
        mGetRequest = new GetCredentialRequest.Builder(Bundle.EMPTY).addCredentialOption(
                new CredentialOption(Credential.TYPE_PASSWORD_CREDENTIAL, Bundle.EMPTY,
                        Bundle.EMPTY, false)).build();
        mCreateRequest = new CreateCredentialRequest.Builder(
                Credential.TYPE_PASSWORD_CREDENTIAL,
                Bundle.EMPTY, Bundle.EMPTY)
                .setIsSystemProviderRequired(false)
                .setAlwaysSendAppInfoToProvider(false)
                .build();
        mClearRequest = new ClearCredentialStateRequest(Bundle.EMPTY);

        final Slice slice = new Slice.Builder(Uri.parse("foo://bar"), null).addText("some text",
                null, List.of(Slice.HINT_TITLE)).build();
        mRegisterRequest = new RegisterCredentialDescriptionRequest(
                new CredentialDescription(Credential.TYPE_PASSWORD_CREDENTIAL,
                        "{ \"foo\": \"bar\" }",
                        List.of(new CredentialEntry(Credential.TYPE_PASSWORD_CREDENTIAL, slice))));
        mUnregisterRequest = new UnregisterCredentialDescriptionRequest(
                new CredentialDescription(Credential.TYPE_PASSWORD_CREDENTIAL,
                        "{ \"foo\": \"bar\" }",
                        List.of(new CredentialEntry(Credential.TYPE_PASSWORD_CREDENTIAL, slice))));

        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mCredentialManager = new CredentialManager(context, mMockCredentialManagerService);
        mExecutor = Runnable::run;
        mPackageName = context.getOpPackageName();
    }

    @Test
    public void testGetCredential_nullRequest() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.getCredential(null, mMockActivity, null, mExecutor,
                        result -> {
                        }));
    }

    @Test
    public void testGetCredential_nullActivity() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.getCredential(mGetRequest, null, null, mExecutor,
                        result -> {
                        }));
    }

    @Test
    public void testGetCredential_nullExecutor() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.getCredential(mGetRequest, mMockActivity, null, null,
                        result -> {
                        }));
    }

    @Test
    public void testGetCredential_nullCallback() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.getCredential(mGetRequest, mMockActivity, null, null,
                        null));
    }

    @Test
    public void testGetCredential_noCredential() throws RemoteException {
        ArgumentCaptor<IGetCredentialCallback> callbackCaptor = ArgumentCaptor.forClass(
                IGetCredentialCallback.class);
        ArgumentCaptor<GetCredentialException> errorCaptor = ArgumentCaptor.forClass(
                GetCredentialException.class);

        OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback = mock(
                OutcomeReceiver.class);

        when(mMockCredentialManagerService.executeGetCredential(any(), callbackCaptor.capture(),
                any())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.getCredential(mGetRequest, mMockActivity, null, mExecutor, callback);
        verify(mMockCredentialManagerService).executeGetCredential(any(), any(), eq(mPackageName));

        callbackCaptor.getValue().onError(GetCredentialException.TYPE_NO_CREDENTIAL,
                "no credential found");
        verify(callback).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getType()).isEqualTo(
                GetCredentialException.TYPE_NO_CREDENTIAL);
    }

    @Test
    public void testGetCredential_alreadyCancelled() throws RemoteException {
        final CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        mCredentialManager.getCredential(mGetRequest, mMockActivity, cancellation, mExecutor,
                result -> {
                });

        verify(mMockCredentialManagerService, never()).executeGetCredential(any(), any(), any());
    }

    @Test
    public void testGetCredential_cancel() throws RemoteException {
        final ICancellationSignal serviceSignal = mock(ICancellationSignal.class);
        final CancellationSignal cancellation = new CancellationSignal();

        OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback = mock(
                OutcomeReceiver.class);

        when(mMockCredentialManagerService.executeGetCredential(any(), any(), any())).thenReturn(
                serviceSignal);

        mCredentialManager.getCredential(mGetRequest, mMockActivity, cancellation, mExecutor,
                callback);

        verify(mMockCredentialManagerService).executeGetCredential(any(), any(), eq(mPackageName));

        cancellation.cancel();
        verify(serviceSignal).cancel();
    }

    @Test
    public void testGetCredential_success() throws RemoteException {
        final Credential cred = new Credential(Credential.TYPE_PASSWORD_CREDENTIAL, Bundle.EMPTY);

        ArgumentCaptor<IGetCredentialCallback> callbackCaptor = ArgumentCaptor.forClass(
                IGetCredentialCallback.class);
        ArgumentCaptor<GetCredentialResponse> responseCaptor = ArgumentCaptor.forClass(
                GetCredentialResponse.class);

        OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback = mock(
                OutcomeReceiver.class);

        when(mMockCredentialManagerService.executeGetCredential(any(), callbackCaptor.capture(),
                any())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.getCredential(mGetRequest, mMockActivity, null, mExecutor, callback);
        verify(mMockCredentialManagerService).executeGetCredential(any(), any(), eq(mPackageName));

        callbackCaptor.getValue().onResponse(new GetCredentialResponse(cred));
        verify(callback).onResult(responseCaptor.capture());

        assertThat(responseCaptor.getValue().getCredential().getType()).isEqualTo(cred.getType());
    }

    @Test
    public void testCreateCredential_nullRequest() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.createCredential(null, mMockActivity, null, mExecutor,
                        result -> {
                        }));
    }

    @Test
    public void testCreateCredential_nullActivity() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.createCredential(mCreateRequest, null, null, mExecutor,
                        result -> {
                        }));
    }

    @Test
    public void testCreateCredential_nullExecutor() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.createCredential(mCreateRequest, mMockActivity, null, null,
                        result -> {
                        }));
    }

    @Test
    public void testCreateCredential_nullCallback() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.createCredential(mCreateRequest, mMockActivity, null,
                        mExecutor, null));
    }

    @Test
    public void testCreateCredential_alreadyCancelled() throws RemoteException {
        final CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        mCredentialManager.createCredential(mCreateRequest, mMockActivity, cancellation, mExecutor,
                result -> {
                });

        verify(mMockCredentialManagerService, never()).executeCreateCredential(any(), any(), any());
    }

    @Test
    public void testCreateCredential_cancel() throws RemoteException {
        final ICancellationSignal serviceSignal = mock(ICancellationSignal.class);
        final CancellationSignal cancellation = new CancellationSignal();

        OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback = mock(
                OutcomeReceiver.class);

        when(mMockCredentialManagerService.executeCreateCredential(any(), any(), any())).thenReturn(
                serviceSignal);

        mCredentialManager.createCredential(mCreateRequest, mMockActivity, cancellation, mExecutor,
                callback);

        verify(mMockCredentialManagerService).executeCreateCredential(any(), any(),
                eq(mPackageName));

        cancellation.cancel();
        verify(serviceSignal).cancel();
    }

    @Test
    public void testCreateCredential_failed() throws RemoteException {
        ArgumentCaptor<ICreateCredentialCallback> callbackCaptor = ArgumentCaptor.forClass(
                ICreateCredentialCallback.class);
        ArgumentCaptor<CreateCredentialException> errorCaptor = ArgumentCaptor.forClass(
                CreateCredentialException.class);

        OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback = mock(
                OutcomeReceiver.class);

        when(mMockCredentialManagerService.executeCreateCredential(any(), callbackCaptor.capture(),
                any())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.createCredential(mCreateRequest, mMockActivity, null, mExecutor,
                callback);
        verify(mMockCredentialManagerService).executeCreateCredential(any(), any(),
                eq(mPackageName));

        callbackCaptor.getValue().onError(CreateCredentialException.TYPE_UNKNOWN, "unknown error");
        verify(callback).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getType()).isEqualTo(
                CreateCredentialException.TYPE_UNKNOWN);
    }

    @Test
    public void testCreateCredential_success() throws RemoteException {
        final Bundle responseData = new Bundle();
        responseData.putString("foo", "bar");

        ArgumentCaptor<ICreateCredentialCallback> callbackCaptor = ArgumentCaptor.forClass(
                ICreateCredentialCallback.class);
        ArgumentCaptor<CreateCredentialResponse> responseCaptor = ArgumentCaptor.forClass(
                CreateCredentialResponse.class);

        OutcomeReceiver<CreateCredentialResponse, CreateCredentialException> callback = mock(
                OutcomeReceiver.class);

        when(mMockCredentialManagerService.executeCreateCredential(any(), callbackCaptor.capture(),
                any())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.createCredential(mCreateRequest, mMockActivity, null, mExecutor,
                callback);
        verify(mMockCredentialManagerService).executeCreateCredential(any(), any(),
                eq(mPackageName));

        callbackCaptor.getValue().onResponse(new CreateCredentialResponse(responseData));
        verify(callback).onResult(responseCaptor.capture());

        assertBundleEquals(responseCaptor.getValue().getData(), responseData);
    }

    @Test
    public void testClearCredentialState_nullRequest() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.clearCredentialState(null, null, mExecutor, result -> {
                }));
    }

    @Test
    public void testClearCredentialState_nullExecutor() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.clearCredentialState(mClearRequest, null, null, result -> {
                }));
    }

    @Test
    public void testClearCredentialState_nullCallback() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.clearCredentialState(mClearRequest, null, mExecutor,
                        null));
    }

    @Test
    public void testClearCredential_alreadyCancelled() throws RemoteException {
        final CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        mCredentialManager.clearCredentialState(mClearRequest, cancellation, mExecutor, result -> {
        });

        verify(mMockCredentialManagerService, never()).clearCredentialState(any(), any(), any());
    }

    @Test
    public void testClearCredential_cancel() throws RemoteException {
        final ICancellationSignal serviceSignal = mock(ICancellationSignal.class);
        final CancellationSignal cancellation = new CancellationSignal();

        OutcomeReceiver<Void, ClearCredentialStateException> callback = mock(OutcomeReceiver.class);

        when(mMockCredentialManagerService.clearCredentialState(any(), any(), any())).thenReturn(
                serviceSignal);

        mCredentialManager.clearCredentialState(mClearRequest, cancellation, mExecutor, callback);

        verify(mMockCredentialManagerService).clearCredentialState(any(), any(), eq(mPackageName));

        cancellation.cancel();
        verify(serviceSignal).cancel();
    }

    @Test
    public void testClearCredential_failed() throws RemoteException {
        ArgumentCaptor<IClearCredentialStateCallback> callbackCaptor = ArgumentCaptor.forClass(
                IClearCredentialStateCallback.class);
        ArgumentCaptor<ClearCredentialStateException> errorCaptor = ArgumentCaptor.forClass(
                ClearCredentialStateException.class);

        OutcomeReceiver<Void, ClearCredentialStateException> callback = mock(OutcomeReceiver.class);

        when(mMockCredentialManagerService.clearCredentialState(any(), callbackCaptor.capture(),
                any())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.clearCredentialState(mClearRequest, null, mExecutor, callback);
        verify(mMockCredentialManagerService).clearCredentialState(any(), any(), eq(mPackageName));

        callbackCaptor.getValue().onError(ClearCredentialStateException.TYPE_UNKNOWN,
                "unknown error");
        verify(callback).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getType()).isEqualTo(
                ClearCredentialStateException.TYPE_UNKNOWN);
    }

    @Test
    public void testClearCredential_success() throws RemoteException {
        ArgumentCaptor<IClearCredentialStateCallback> callbackCaptor = ArgumentCaptor.forClass(
                IClearCredentialStateCallback.class);

        OutcomeReceiver<Void, ClearCredentialStateException> callback = mock(OutcomeReceiver.class);

        when(mMockCredentialManagerService.clearCredentialState(any(), callbackCaptor.capture(),
                any())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.clearCredentialState(mClearRequest, null, mExecutor, callback);
        verify(mMockCredentialManagerService).clearCredentialState(any(), any(), eq(mPackageName));

        callbackCaptor.getValue().onSuccess();
        verify(callback).onResult(any());
    }

    @Test
    public void testListEnabledProviders_nullExecutor() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.listEnabledProviders(null, null, result -> {
                }));

    }

    @Test
    public void testListEnabledProviders_nullCallback() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.listEnabledProviders(null, mExecutor, null));

    }

    @Test
    public void testListEnabledProviders_alreadyCancelled() throws RemoteException {
        final CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        mCredentialManager.listEnabledProviders(cancellation, mExecutor, result -> {
        });

        verify(mMockCredentialManagerService, never()).listEnabledProviders(any());
    }

    @Test
    public void testListEnabledProviders_cancel() throws RemoteException {
        final ICancellationSignal serviceSignal = mock(ICancellationSignal.class);
        final CancellationSignal cancellation = new CancellationSignal();

        OutcomeReceiver<ListEnabledProvidersResponse, ListEnabledProvidersException> callback =
                mock(OutcomeReceiver.class);

        when(mMockCredentialManagerService.listEnabledProviders(any())).thenReturn(serviceSignal);

        mCredentialManager.listEnabledProviders(cancellation, mExecutor, callback);

        verify(mMockCredentialManagerService).listEnabledProviders(any());

        cancellation.cancel();
        verify(serviceSignal).cancel();
    }

    @Test
    public void testListEnabledProviders_failed() throws RemoteException {
        ArgumentCaptor<IListEnabledProvidersCallback> callbackCaptor = ArgumentCaptor.forClass(
                IListEnabledProvidersCallback.class);
        ArgumentCaptor<ListEnabledProvidersException> errorCaptor = ArgumentCaptor.forClass(
                ListEnabledProvidersException.class);

        OutcomeReceiver<ListEnabledProvidersResponse, ListEnabledProvidersException> callback =
                mock(OutcomeReceiver.class);

        when(mMockCredentialManagerService.listEnabledProviders(
                callbackCaptor.capture())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.listEnabledProviders(null, mExecutor, callback);
        verify(mMockCredentialManagerService).listEnabledProviders(any());

        final String errorType = "type";
        callbackCaptor.getValue().onError("type", "unknown error");
        verify(callback).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getType()).isEqualTo(errorType);
    }

    @Test
    public void testListEnabledProviders_success() throws RemoteException {
        ListEnabledProvidersResponse response = ListEnabledProvidersResponse.create(
                List.of("foo", "bar", "baz"));

        OutcomeReceiver<ListEnabledProvidersResponse, ListEnabledProvidersException> callback =
                mock(OutcomeReceiver.class);

        ArgumentCaptor<IListEnabledProvidersCallback> callbackCaptor = ArgumentCaptor.forClass(
                IListEnabledProvidersCallback.class);
        ArgumentCaptor<ListEnabledProvidersResponse> responseCaptor = ArgumentCaptor.forClass(
                ListEnabledProvidersResponse.class);

        when(mMockCredentialManagerService.listEnabledProviders(
                callbackCaptor.capture())).thenReturn(mock(ICancellationSignal.class));
        mCredentialManager.listEnabledProviders(null, mExecutor, callback);

        verify(mMockCredentialManagerService).listEnabledProviders(any());

        callbackCaptor.getValue().onResponse(response);

        verify(callback).onResult(responseCaptor.capture());
        assertThat(responseCaptor.getValue().getProviderComponentNames()).containsExactlyElementsIn(
                response.getProviderComponentNames());
    }

    @Test
    public void testSetEnabledProviders_nullProviders() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.setEnabledProviders(null, 0, mExecutor, response -> {
                }));

    }

    @Test
    public void testSetEnabledProviders_nullExecutor() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.setEnabledProviders(List.of("foo"), 0, null, response -> {
                }));

    }

    @Test
    public void testSetEnabledProviders_nullCallback() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.setEnabledProviders(List.of("foo"), 0, mExecutor, null));

    }

    @Test
    public void testSetEnabledProviders_failed() throws RemoteException {
        OutcomeReceiver<Void, SetEnabledProvidersException> callback = mock(OutcomeReceiver.class);

        ArgumentCaptor<ISetEnabledProvidersCallback> callbackCaptor = ArgumentCaptor.forClass(
                ISetEnabledProvidersCallback.class);
        ArgumentCaptor<SetEnabledProvidersException> errorCaptor = ArgumentCaptor.forClass(
                SetEnabledProvidersException.class);

        final List<String> providers = List.of("foo", "bar");
        final int userId = 0;
        mCredentialManager.setEnabledProviders(providers, userId, mExecutor, callback);
        verify(mMockCredentialManagerService).setEnabledProviders(eq(providers), eq(0),
                callbackCaptor.capture());

        final String errorType = "unknown";
        final String errorMessage = "Unknown error";
        callbackCaptor.getValue().onError(errorType, errorMessage);
        verify(callback).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getType()).isEqualTo(errorType);
        assertThat(errorCaptor.getValue().getMessage()).isEqualTo(errorMessage);
    }

    @Test
    public void testSetEnabledProviders_success() throws RemoteException {
        OutcomeReceiver<Void, SetEnabledProvidersException> callback = mock(OutcomeReceiver.class);

        ArgumentCaptor<ISetEnabledProvidersCallback> callbackCaptor = ArgumentCaptor.forClass(
                ISetEnabledProvidersCallback.class);

        final List<String> providers = List.of("foo", "bar");
        final int userId = 0;
        mCredentialManager.setEnabledProviders(providers, userId, mExecutor, callback);

        verify(mMockCredentialManagerService).setEnabledProviders(eq(providers), eq(0),
                callbackCaptor.capture());

        callbackCaptor.getValue().onResponse();
        verify(callback).onResult(any());
    }

    @Test
    public void testRegisterCredentialDescription_nullRequest() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.registerCredentialDescription(null));
    }

    @Test
    public void testRegisterCredentialDescription_success() throws RemoteException {
        mCredentialManager.registerCredentialDescription(mRegisterRequest);
        verify(mMockCredentialManagerService).registerCredentialDescription(same(mRegisterRequest),
                eq(mPackageName));
    }

    @Test
    public void testUnregisterCredentialDescription_nullRequest() {
        assertThrows(NullPointerException.class,
                () -> mCredentialManager.unregisterCredentialDescription(null));
    }

    @Test
    public void testUnregisterCredentialDescription_success() throws RemoteException {
        mCredentialManager.unregisterCredentialDescription(mUnregisterRequest);
        verify(mMockCredentialManagerService).unregisterCredentialDescription(
                same(mUnregisterRequest), eq(mPackageName));
    }
}
