package com.driveedge.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
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
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SignInActivity extends AppCompatActivity {
  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  @NonNull
  private final EdgeApiClient edgeApiClient = new EdgeApiClient();
  @NonNull
  private final EdgeFlowController edgeFlowController = new EdgeFlowController(edgeApiClient);
  @Nullable
  private EdgeContextStore edgeContextStore;
  @NonNull
  private volatile EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();

  private TextView edgeContextView;
  private TextView signInSubtitleView;
  private EditText driverCodeInput;
  private EditText driverPinInput;
  private Button syncButton;
  private Button signInButton;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_sign_in);

    edgeContextView = findViewById(R.id.edgeContextView);
    signInSubtitleView = findViewById(R.id.signInSubtitleView);
    driverCodeInput = findViewById(R.id.driverCodeInput);
    driverPinInput = findViewById(R.id.driverPinInput);
    syncButton = findViewById(R.id.syncButton);
    signInButton = findViewById(R.id.signInButton);

    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = edgeContextStore.load();

    syncButton.setOnClickListener(v -> refreshEdgeContext(false));
    signInButton.setOnClickListener(v -> signInDriver());

    if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, edgeLocalContext, SignInActivity.class)) {
      return;
    }

    renderEdgeContext();
    refreshEdgeContext(true);
  }

  @Override
  protected void onDestroy() {
    edgeIoExecutor.shutdownNow();
    super.onDestroy();
  }

  private void refreshEdgeContext(boolean silentFailure) {
    setActionsEnabled(false);
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
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, SignInActivity.class)) {
            return;
          }
          renderEdgeContext();
          setActionsEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (!silentFailure) {
            Toast.makeText(
              this,
              getString(R.string.edge_sync_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)),
              Toast.LENGTH_SHORT
            ).show();
          }
          renderEdgeContext();
          setActionsEnabled(true);
        });
      }
    });
  }

  private void signInDriver() {
    if (!edgeLocalContext.isSignInAllowed()) {
      Toast.makeText(this, edgeFlowController.resolveStatusMessage(edgeLocalContext), Toast.LENGTH_SHORT).show();
      return;
    }
    String driverCode = driverCodeInput.getText() == null ? "" : driverCodeInput.getText().toString().trim();
    String pin = driverPinInput.getText() == null ? "" : driverPinInput.getText().toString();
    if (driverCode.isEmpty()) {
      Toast.makeText(this, R.string.edge_missing_driver_code, Toast.LENGTH_SHORT).show();
      return;
    }
    if (pin.isEmpty()) {
      Toast.makeText(this, R.string.edge_missing_driver_pin, Toast.LENGTH_SHORT).show();
      return;
    }

    setActionsEnabled(false);
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext synced = edgeFlowController.syncContext(store.load());
        store.save(synced);
        edgeLocalContext = synced;
        if (!synced.isSignInAllowed()) {
          runOnUiThread(() -> {
            if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, synced, SignInActivity.class)) {
              return;
            }
            renderEdgeContext();
            setActionsEnabled(true);
            Toast.makeText(this, edgeFlowController.resolveStatusMessage(synced), Toast.LENGTH_SHORT).show();
          });
          return;
        }

        EdgeLocalContext updated = edgeApiClient.signIn(synced, driverCode, pin);
        store.save(updated);
        edgeLocalContext = updated;
        runOnUiThread(() -> {
          driverPinInput.setText("");
          Toast.makeText(this, R.string.edge_sign_in_success, Toast.LENGTH_SHORT).show();
          EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, SignInActivity.class);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          setActionsEnabled(true);
          Toast.makeText(
            this,
            getString(R.string.edge_sign_in_failed, edgeFlowController.formatEdgeError(edgeLocalContext, error)),
            Toast.LENGTH_SHORT
          ).show();
        });
      }
    });
  }

  private void renderEdgeContext() {
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      edgeLocalContext = store.load();
    }
    EdgeLocalContext context = edgeLocalContext;
    edgeContextView.setText(getString(
      R.string.edge_context_format,
      EdgeFlowController.displayValue(context.enterpriseName, context.enterpriseId),
      edgeFlowController.displayBindStatus(context),
      EdgeFlowController.displayValue(context.fleetName, context.fleetId),
      EdgeFlowController.displayValue(context.vehiclePlateNumber, context.vehicleId),
      EdgeFlowController.displayDriverValue(context),
      EdgeFlowController.displayText(context.signedInAt),
      EdgeFlowController.displayText(context.configVersion),
      EdgeFlowController.displayText(context.lastSyncAt),
      "事件上报：待触发"
    ));
    signInSubtitleView.setText(edgeFlowController.resolveStatusMessage(context));
    setActionsEnabled(true);
  }

  private void setActionsEnabled(boolean enabled) {
    boolean canSignIn = enabled && edgeLocalContext.isSignInAllowed() && !edgeLocalContext.hasActiveSession();
    syncButton.setEnabled(enabled);
    signInButton.setEnabled(canSignIn);
    int formVisibility = edgeLocalContext.isSignInAllowed() ? View.VISIBLE : View.GONE;
    driverCodeInput.setVisibility(formVisibility);
    driverPinInput.setVisibility(formVisibility);
    signInButton.setVisibility(formVisibility);
  }

  private void navigateToCapture() {
    Intent intent = new Intent(this, MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
    finish();
  }

}
