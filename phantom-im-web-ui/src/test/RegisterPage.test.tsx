import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { RegisterPage, validateRegistration } from "../features/auth/RegisterPage";

describe("registration form", () => {
  it("validates all field boundaries locally", () => {
    expect(validateRegistration({ nickname: "", login_name: "a-b", password: "short", password_confirm: "x" }))
      .toEqual(expect.objectContaining({
        nickname: expect.any(String), login_name: expect.any(String),
        password: expect.any(String), password_confirm: expect.any(String),
      }));
  });

  it("shows field errors and never submits invalid data", async () => {
    render(<MemoryRouter><RegisterPage /></MemoryRouter>);
    fireEvent.click(screen.getByTestId("register-submit"));
    expect(screen.getByText("昵称长度必须为 1 至 64 个字符")).toBeInTheDocument();
    expect(screen.getByText("登录名必须为 4 至 32 位字母、数字或下划线")).toBeInTheDocument();
  });
});
