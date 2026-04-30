package com.driveedge.app.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.driveedge.app.R;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EdgeDisabledActivity extends AppCompatActivity {
  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  private final EdgeFlowController edgeFlowController = new EdgeFlowController();
  @Nullable
  private EdgeContextStore edgeContextStore;
  private EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();

  private TextView deviceCodeView;
  private TextView disabledMessageView;
  private Button refreshButton;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edge_disabled);

    deviceCodeView = findViewById(R.id.deviceCodeView);
    disabledMessageView = findViewById(R.id.disabledMessageView);
    refreshButton = findViewById(R.id.refreshButton);

    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = edgeContextStore.load();
    refreshButton.setOnClickListener(v -> refreshStatus(false));

    if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, edgeLocalContext, EdgeDisabledActivity.class)) {
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
    refreshButton.setEnabled(false);
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
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, EdgeDisabledActivity.class)) {
            return;
          }
          render();
          refreshButton.setEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (!silentFailure) {
            Toast.makeText(this, edgeFlowController.formatEdgeError(edgeLocalContext, error), Toast.LENGTH_SHORT).show();
          }
          render();
          refreshButton.setEnabled(true);
        });
      }
    });
  }

  private void render() {
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      edgeLocalContext = store.load();
    }
    deviceCodeView.setText(getString(R.string.edge_disabled_device_code, EdgeFlowController.displayText(edgeLocalContext.deviceCode)));
    disabledMessageView.setText(edgeFlowController.resolveStatusMessage(edgeLocalContext));
  }
}
