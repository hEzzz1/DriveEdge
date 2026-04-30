package com.driveedge.app.edge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class EdgeFlowControllerTest {
  private final EdgeFlowController flowController = new EdgeFlowController();

  @Test
  public void resolveDestinationUsesEffectiveStage() {
    EdgeLocalContext context = EdgeLocalContext.empty();
    context.effectiveStage = "CLAIM_ENTERPRISE";

    assertEquals(EdgeFlowController.Destination.CLAIM_ENTERPRISE, flowController.resolveDestination(context));
  }

  @Test
  public void resolveStatusMessageShowsWaitingVehicleHint() {
    EdgeLocalContext context = EdgeLocalContext.empty();
    context.effectiveStage = "WAITING_VEHICLE";

    assertEquals("设备已绑定企业，待分配车辆", flowController.resolveStatusMessage(context));
  }

  @Test
  public void resolveStatusMessageShowsClaimEnterpriseHint() {
    EdgeLocalContext context = EdgeLocalContext.empty();
    context.effectiveStage = "CLAIM_ENTERPRISE";

    assertEquals("请输入企业激活码", flowController.resolveStatusMessage(context));
  }

  @Test
  public void formatEdgeErrorSupportsBoundOtherEnterpriseCode() {
    EdgeLocalContext context = EdgeLocalContext.empty();

    assertEquals(
      "设备已绑定其他企业，请联系管理员先解绑",
      flowController.formatEdgeError(context, new EdgeApiException(40911, "bound_other_enterprise", null))
    );
  }
}
