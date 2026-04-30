package com.driveedge.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.driveedge.app.R;
import com.driveedge.app.edge.EdgeApiClient;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BindEnterpriseActivity extends AppCompatActivity {
  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  private final EdgeApiClient edgeApiClient = new EdgeApiClient();
  private final EdgeFlowController edgeFlowController = new EdgeFlowController(edgeApiClient);
  @Nullable
  private EdgeContextStore edgeContextStore;
  private EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();

  private TextView deviceCodeView;
  private TextView bindStatusView;
  private TextView rejectReasonView;
  private EditText bindCodeInput;
  private EditText bindRemarkInput;
  private Button submitButton;
  private Button refreshButton;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_bind_enterprise);

    deviceCodeView = findViewById(R.id.deviceCodeView);
    bindStatusView = findViewById(R.id.bindStatusView);
    rejectReasonView = findViewById(R.id.rejectReasonView);
    bindCodeInput = findViewById(R.id.bindCodeInput);
    bindRemarkInput = findViewById(R.id.bindRemarkInput);
    submitButton = findViewById(R.id.submitBindButton);
    refreshButton = findViewById(R.id.refreshButton);

    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = edgeContextStore.load();

    submitButton.setOnClickListener(v -> submitBindRequest());
    refreshButton.setOnClickListener(v -> refreshStatus(false));

    if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, edgeLocalContext, BindEnterpriseActivity.class)) {
      return;
    }
    render();
    refreshStatus(true);
  }

  @Override
  protected void onDestroy() {
    edgeIoExecutor.shutdownNow();
    super.onDestroy();
  }

  private void refreshStatus(boolean silentFailure) {
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
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, BindEnterpriseActivity.class)) {
            return;
          }
          render();
          setActionsEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (!silentFailure) {
            Toast.makeText(this, edgeFlowController.formatEdgeError(edgeLocalContext, error), Toast.LENGTH_SHORT).show();
          }
          render();
          setActionsEnabled(true);
        });
      }
    });
  }

  private void submitBindRequest() {
    String bindCode = bindCodeInput.getText() == null ? "" : bindCodeInput.getText().toString().trim();
    String remark = bindRemarkInput.getText() == null ? "" : bindRemarkInput.getText().toString().trim();
    if (bindCode.isEmpty()) {
      Toast.makeText(this, R.string.edge_bind_code_required, Toast.LENGTH_SHORT).show();
      return;
    }

    setActionsEnabled(false);
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext updated = edgeApiClient.submitBindRequest(store.load(), bindCode, remark);
        store.save(updated);
        edgeLocalContext = updated;
        runOnUiThread(() -> {
          String enterpriseName = updated.resolvedBindEnterpriseName();
          Toast.makeText(this, getString(R.string.edge_bind_submit_success_with_enterprise, enterpriseName), Toast.LENGTH_SHORT).show();
          bindCodeInput.setText("");
          EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, BindEnterpriseActivity.class);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          setActionsEnabled(true);
          Toast.makeText(this, edgeFlowController.formatEdgeError(edgeLocalContext, error), Toast.LENGTH_SHORT).show();
        });
      }
    });
  }

  private void render() {
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      edgeLocalContext = store.load();
    }
    deviceCodeView.setText(EdgeFlowController.displayText(edgeLocalContext.deviceCode));
    bindStatusView.setText(edgeFlowController.resolveStatusMessage(edgeLocalContext));
    boolean showFeedback = edgeLocalContext.hasRejectedBindRequest() || edgeLocalContext.hasExpiredBindRequest();
    rejectReasonView.setVisibility(showFeedback ? View.VISIBLE : View.GONE);
    if (edgeLocalContext.hasRejectedBindRequest()) {
      rejectReasonView.setText(getString(
        R.string.edge_bind_reject_reason,
        EdgeFlowController.displayText(edgeLocalContext.bindRequestRejectReason)
      ));
      return;
    }
    rejectReasonView.setText(R.string.edge_bind_expired_hint);
  }

  private void setActionsEnabled(boolean enabled) {
    submitButton.setEnabled(enabled);
    refreshButton.setEnabled(enabled);
  }
}
