package com.driveedge.app.ui;

import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;
import com.driveedge.app.R;
import com.driveedge.app.edge.EdgeActivationCodeParser;
import com.driveedge.app.edge.EdgeApiClient;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeDeviceCodeProvider;
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EdgeLaunchActivity extends AppCompatActivity {
  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  private final EdgeActivationCodeParser edgeActivationCodeParser = new EdgeActivationCodeParser();
  private final EdgeDeviceCodeProvider edgeDeviceCodeProvider = new EdgeDeviceCodeProvider();
  private final EdgeApiClient edgeApiClient = new EdgeApiClient();
  private final EdgeFlowController edgeFlowController = new EdgeFlowController(edgeApiClient);
  @Nullable
  private EdgeContextStore edgeContextStore;
  private EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();

  private TextView launchStatusView;
  private TextView deviceCodeView;
  private EditText enterpriseActivationCodeInput;
  private Button scanQrButton;
  private Button claimButton;
  private Button resetButton;
  private final ActivityResultLauncher<String> cameraPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
      if (granted) {
        openQrScanner();
        return;
      }
      Toast.makeText(this, R.string.permissions_denied, Toast.LENGTH_SHORT).show();
    });
  private final ActivityResultLauncher<ScanOptions> scanLauncher =
    registerForActivityResult(new ScanContract(), this::handleScanResult);

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edge_launch);

    launchStatusView = findViewById(R.id.launchStatusView);
    deviceCodeView = findViewById(R.id.deviceCodeView);
    enterpriseActivationCodeInput = findViewById(R.id.enterpriseActivationCodeInput);
    scanQrButton = findViewById(R.id.scanQrButton);
    claimButton = findViewById(R.id.claimButton);
    resetButton = findViewById(R.id.resetButton);

    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = ensureDeviceCode(edgeContextStore.load());
    scanQrButton.setOnClickListener(v -> requestQrScan());
    claimButton.setOnClickListener(v -> claimEnterprise());
    resetButton.setOnClickListener(v -> resetDeviceBinding());

    renderLaunchContext();
    if (edgeLocalContext.hasDeviceIdentity()) {
      syncStoredContext(true);
    } else {
      persistClaimState();
    }
  }

  @Override
  protected void onDestroy() {
    edgeIoExecutor.shutdownNow();
    super.onDestroy();
  }

  private void syncStoredContext(boolean silentFailure) {
    setActionsEnabled(false);
    launchStatusView.setText(R.string.edge_launch_loading);
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext updated = edgeFlowController.syncContext(store.load());
        store.save(updated);
        edgeLocalContext = updated;
        runOnUiThread(() -> {
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, EdgeLaunchActivity.class)) {
            return;
          }
          renderLaunchContext();
          setActionsEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          renderLaunchContext();
          setActionsEnabled(true);
          if (!silentFailure) {
            launchStatusView.setText(getString(R.string.edge_launch_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)));
            Toast.makeText(this, edgeFlowController.formatEdgeError(edgeLocalContext, error), Toast.LENGTH_SHORT).show();
          }
        });
      }
    });
  }

  private void claimEnterprise() {
    String enterpriseActivationCode = enterpriseActivationCodeInput.getText() == null
      ? ""
      : enterpriseActivationCodeInput.getText().toString().trim();
    if (enterpriseActivationCode.isEmpty()) {
      Toast.makeText(this, R.string.edge_missing_enterprise_activation_code, Toast.LENGTH_SHORT).show();
      return;
    }

    setActionsEnabled(false);
    launchStatusView.setText(R.string.edge_launch_loading);
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext prepared = ensureDeviceCode(store.load());
        EdgeLocalContext updated = edgeApiClient.claimDevice(prepared, enterpriseActivationCode, resolveLocalDeviceName(prepared));
        store.save(updated);
        edgeLocalContext = updated;
        runOnUiThread(() -> {
          enterpriseActivationCodeInput.setText("");
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, EdgeLaunchActivity.class)) {
            return;
          }
          renderLaunchContext();
          setActionsEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          launchStatusView.setText(getString(R.string.edge_launch_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)));
          setActionsEnabled(true);
          Toast.makeText(this, edgeFlowController.formatEdgeError(edgeLocalContext, error), Toast.LENGTH_SHORT).show();
        });
      }
    });
  }

  private void renderLaunchContext() {
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      edgeLocalContext = ensureDeviceCode(store.load());
    }
    deviceCodeView.setText(EdgeFlowController.displayText(edgeLocalContext.deviceCode));
    if (!edgeLocalContext.hasDeviceIdentity()) {
      launchStatusView.setText(R.string.edge_launch_ready);
      return;
    }
    launchStatusView.setText(edgeFlowController.resolveStatusMessage(edgeLocalContext));
  }

  private void requestQrScan() {
    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
      == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      openQrScanner();
      return;
    }
    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
  }

  private void openQrScanner() {
    ScanOptions options = new ScanOptions();
    options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
    options.setPrompt(getString(R.string.edge_scan_qr_prompt));
    options.setBeepEnabled(false);
    options.setOrientationLocked(false);
    options.addExtra(Intents.Scan.FORMATS, ScanOptions.QR_CODE);
    scanLauncher.launch(options);
  }

  private void handleScanResult(ScanIntentResult result) {
    if (result == null || result.getContents() == null) {
      return;
    }
    String activationCode = edgeActivationCodeParser.parse(result.getContents());
    if (activationCode == null || activationCode.isEmpty()) {
      Toast.makeText(this, R.string.edge_scan_qr_invalid, Toast.LENGTH_SHORT).show();
      return;
    }
    enterpriseActivationCodeInput.setText(activationCode);
    claimEnterprise();
  }

  private void resetDeviceBinding() {
    EdgeContextStore store = edgeContextStore;
    EdgeLocalContext cleared = EdgeLocalContext.empty();
    cleared.deviceCode = edgeDeviceCodeProvider.resolve(getApplicationContext(), edgeLocalContext.deviceCode);
    cleared.effectiveStage = "CLAIM_ENTERPRISE";
    if (store != null) {
      store.save(cleared);
    }
    edgeLocalContext = cleared;
    enterpriseActivationCodeInput.setText("");
    renderLaunchContext();
    setActionsEnabled(true);
  }

  private void persistClaimState() {
    EdgeContextStore store = edgeContextStore;
    if (store == null) {
      return;
    }
    EdgeLocalContext claimContext = edgeLocalContext.copy();
    claimContext.clearDeviceRuntime();
    claimContext.effectiveStage = "CLAIM_ENTERPRISE";
    store.save(claimContext);
    edgeLocalContext = claimContext;
    renderLaunchContext();
  }

  private void setActionsEnabled(boolean enabled) {
    scanQrButton.setEnabled(enabled);
    claimButton.setEnabled(enabled);
    resetButton.setEnabled(enabled);
  }

  @NonNull
  private EdgeLocalContext ensureDeviceCode(@NonNull EdgeLocalContext context) {
    String deviceCode = edgeDeviceCodeProvider.resolve(getApplicationContext(), context.deviceCode);
    if (deviceCode.equals(context.deviceCode)) {
      return context;
    }
    EdgeLocalContext updated = context.copy();
    updated.deviceCode = deviceCode;
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      store.save(updated);
    }
    return updated;
  }

  @Nullable
  private String resolveLocalDeviceName(@NonNull EdgeLocalContext context) {
    if (context.deviceName != null && !context.deviceName.trim().isEmpty()) {
      return context.deviceName;
    }
    String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
    String model = Build.MODEL == null ? "" : Build.MODEL.trim();
    if (manufacturer.isEmpty() && model.isEmpty()) {
      return null;
    }
    if (model.startsWith(manufacturer) || manufacturer.isEmpty()) {
      return model;
    }
    return manufacturer + " " + model;
  }
}
