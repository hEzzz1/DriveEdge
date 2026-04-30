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

import com.driveedge.app.R;
import com.driveedge.app.edge.EdgeApiClient;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeDeviceCodeProvider;
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EdgeLaunchActivity extends AppCompatActivity {
  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  private final EdgeDeviceCodeProvider edgeDeviceCodeProvider = new EdgeDeviceCodeProvider();
  private final EdgeApiClient edgeApiClient = new EdgeApiClient();
  private final EdgeFlowController edgeFlowController = new EdgeFlowController(edgeApiClient);
  @Nullable
  private EdgeContextStore edgeContextStore;
  private EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();

  private TextView launchStatusView;
  private TextView deviceCodeView;
  private EditText enterpriseActivationCodeInput;
  private Button claimButton;
  private Button resetButton;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edge_launch);

    launchStatusView = findViewById(R.id.launchStatusView);
    deviceCodeView = findViewById(R.id.deviceCodeView);
    enterpriseActivationCodeInput = findViewById(R.id.enterpriseActivationCodeInput);
    claimButton = findViewById(R.id.claimButton);
    resetButton = findViewById(R.id.resetButton);

    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = ensureDeviceCode(edgeContextStore.load());
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
    claimButton.setEnabled(false);
    resetButton.setEnabled(false);
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
          claimButton.setEnabled(true);
          resetButton.setEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          renderLaunchContext();
          claimButton.setEnabled(true);
          resetButton.setEnabled(true);
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

    claimButton.setEnabled(false);
    resetButton.setEnabled(false);
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
          claimButton.setEnabled(true);
          resetButton.setEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          launchStatusView.setText(getString(R.string.edge_launch_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)));
          claimButton.setEnabled(true);
          resetButton.setEnabled(true);
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
    claimButton.setEnabled(true);
    resetButton.setEnabled(true);
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
