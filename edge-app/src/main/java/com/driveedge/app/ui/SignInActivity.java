package com.driveedge.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
  private TextView availableDriversLabelView;
  private TextView availableDriversHintView;
  private Spinner availableDriverSpinner;
  private EditText driverCodeInput;
  private EditText driverPinInput;
  private Button syncButton;
  private Button signInButton;
  @NonNull
  private final List<EdgeApiClient.AvailableDriver> availableDrivers = new ArrayList<>();
  @Nullable
  private ArrayAdapter<String> availableDriverAdapter;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_sign_in);

    edgeContextView = findViewById(R.id.edgeContextView);
    signInSubtitleView = findViewById(R.id.signInSubtitleView);
    availableDriversLabelView = findViewById(R.id.availableDriversLabelView);
    availableDriversHintView = findViewById(R.id.availableDriversHintView);
    availableDriverSpinner = findViewById(R.id.availableDriverSpinner);
    driverCodeInput = findViewById(R.id.driverCodeInput);
    driverPinInput = findViewById(R.id.driverPinInput);
    syncButton = findViewById(R.id.syncButton);
    signInButton = findViewById(R.id.signInButton);

    availableDriverAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
    availableDriverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    availableDriverSpinner.setAdapter(availableDriverAdapter);
    availableDriverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position <= 0 || position - 1 >= availableDrivers.size()) {
          return;
        }
        EdgeApiClient.AvailableDriver driver = availableDrivers.get(position - 1);
        driverCodeInput.setText(driver.getDriverCode());
        driverCodeInput.setSelection(driver.getDriverCode().length());
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
      }
    });

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
        AvailableDriversResult driversResult = loadAvailableDrivers(updated);
        runOnUiThread(() -> {
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, SignInActivity.class)) {
            return;
          }
          applyAvailableDrivers(driversResult.items());
          renderEdgeContext();
          renderAvailableDrivers(driversResult.errorMessage());
          setActionsEnabled(true);
          if (!silentFailure && driversResult.errorMessage() != null) {
            Toast.makeText(this, getString(R.string.edge_available_drivers_failed, driversResult.errorMessage()), Toast.LENGTH_SHORT).show();
          }
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
          applyAvailableDrivers(Collections.emptyList());
          renderEdgeContext();
          renderAvailableDrivers(null);
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
        AvailableDriversResult driversResult = loadAvailableDrivers(synced);
        if (!synced.isSignInAllowed()) {
          runOnUiThread(() -> {
            if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, synced, SignInActivity.class)) {
              return;
            }
            applyAvailableDrivers(driversResult.items());
            renderEdgeContext();
            renderAvailableDrivers(driversResult.errorMessage());
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
      EdgeFlowController.displayValue(context.vehiclePlateNumber, context.vehicleId),
      EdgeFlowController.displayDriverValue(context),
      edgeFlowController.displayBindStatus(context),
      "事件上报：待触发"
    ));
    signInSubtitleView.setText(edgeFlowController.resolveStatusMessage(context));
    setActionsEnabled(true);
  }

  private AvailableDriversResult loadAvailableDrivers(@NonNull EdgeLocalContext context) {
    if (!context.isSignInAllowed() || context.hasActiveSession()) {
      return new AvailableDriversResult(Collections.emptyList(), null);
    }
    try {
      return new AvailableDriversResult(edgeApiClient.fetchAvailableDrivers(context), null);
    } catch (Exception error) {
      return new AvailableDriversResult(Collections.emptyList(), edgeFlowController.formatEdgeError(context, error));
    }
  }

  private void applyAvailableDrivers(@NonNull List<EdgeApiClient.AvailableDriver> drivers) {
    availableDrivers.clear();
    availableDrivers.addAll(drivers);
  }

  private void renderAvailableDrivers(@Nullable String errorMessage) {
    ArrayAdapter<String> adapter = availableDriverAdapter;
    if (adapter == null) {
      return;
    }

    List<String> options = new ArrayList<>();
    options.add(getString(R.string.edge_available_drivers_placeholder));
    for (EdgeApiClient.AvailableDriver driver : availableDrivers) {
      options.add(driver.displayLabel());
    }
    adapter.clear();
    adapter.addAll(options);
    adapter.notifyDataSetChanged();

    int matchedIndex = findDriverIndexByCurrentCode();
    availableDriverSpinner.setSelection(matchedIndex >= 0 ? matchedIndex + 1 : 0, false);

    if (!edgeLocalContext.isSignInAllowed()) {
      availableDriversHintView.setText("");
      return;
    }
    if (errorMessage != null) {
      availableDriversHintView.setText(getString(R.string.edge_available_drivers_failed, errorMessage));
      return;
    }
    if (availableDrivers.isEmpty()) {
      availableDriversHintView.setText(R.string.edge_available_drivers_empty);
      return;
    }
    availableDriversHintView.setText(getString(R.string.edge_available_drivers_hint, availableDrivers.size()));
  }

  private int findDriverIndexByCurrentCode() {
    String currentCode = driverCodeInput.getText() == null ? "" : driverCodeInput.getText().toString().trim();
    if (currentCode.isEmpty()) {
      return -1;
    }
    for (int index = 0; index < availableDrivers.size(); index++) {
      if (currentCode.equals(availableDrivers.get(index).getDriverCode())) {
        return index;
      }
    }
    return -1;
  }

  private void setActionsEnabled(boolean enabled) {
    boolean canSignIn = enabled && edgeLocalContext.isSignInAllowed() && !edgeLocalContext.hasActiveSession();
    syncButton.setEnabled(enabled);
    signInButton.setEnabled(canSignIn);
    int formVisibility = edgeLocalContext.isSignInAllowed() ? View.VISIBLE : View.GONE;
    availableDriversLabelView.setVisibility(formVisibility);
    availableDriverSpinner.setVisibility(formVisibility);
    availableDriverSpinner.setEnabled(canSignIn && !availableDrivers.isEmpty());
    availableDriversHintView.setVisibility(formVisibility);
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

  private static final class AvailableDriversResult {
    @NonNull
    private final List<EdgeApiClient.AvailableDriver> items;
    @Nullable
    private final String errorMessage;

    private AvailableDriversResult(@NonNull List<EdgeApiClient.AvailableDriver> items, @Nullable String errorMessage) {
      this.items = items;
      this.errorMessage = errorMessage;
    }

    @NonNull
    private List<EdgeApiClient.AvailableDriver> items() {
      return items;
    }

    @Nullable
    private String errorMessage() {
      return errorMessage;
    }
  }

}
