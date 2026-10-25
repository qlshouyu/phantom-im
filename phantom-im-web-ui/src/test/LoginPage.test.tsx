import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginPage } from "../features/auth/LoginPage";
import { login } from "../services/api";
import { connect } from "../services/sdk-adapter";

vi.mock("../services/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/api")>();
  return { ...actual, login: vi.fn() };
});

vi.mock("../services/sdk-adapter", () => ({
  connect: vi.fn(),
}));

describe("login page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows the connection failure reason after account login succeeds", async () => {
    vi.mocked(login).mockResolvedValue({
      account_id: "account-1",
      phantom_user_id: "user-1",
      login_name: "lugao3",
      nickname: "高露3",
    });
    vi.mocked(connect).mockRejectedValue(new Error("auth failed code=2003"));

    render(<MemoryRouter><LoginPage /></MemoryRouter>);
    fireEvent.change(screen.getByTestId("login-name"), { target: { value: "lugao3" } });
    fireEvent.change(screen.getByTestId("login-password"), { target: { value: "12345678" } });
    fireEvent.click(screen.getByTestId("login-submit"));

    expect(await screen.findByRole("alert"))
      .toHaveTextContent("即时通信连接失败：auth failed code=2003");
  });
});
