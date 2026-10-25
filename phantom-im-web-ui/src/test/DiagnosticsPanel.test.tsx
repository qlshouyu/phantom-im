import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { DiagnisticsPanel } from "../features/diagnostics/DiagnosticsPanel";
import { useAppStore } from "../store/app-store";

describe("diagnostics panel", () => {
  beforeEach(() => {
    useAppStore.setState({
      account: { account_id: "a", phantom_user_id: "u", login_name: "alice", nickname: "Alice" },
      connection: "online",
      diagnostics: [{ id: "1", at: Date.now(), type: "sync", detail: "{\"type\":\"SYNC\"}" }],
    });
  });

  it("renders live connection and recent SDK event", () => {
    render(<DiagnisticsPanel open onClose={() => undefined} />);
    expect(screen.getByTestId("diagnostic-connection")).toHaveTextContent("online");
    expect(screen.getByText("sync")).toBeInTheDocument();
    expect(screen.getByText("Phantom ID")).toBeInTheDocument();
  });
});
