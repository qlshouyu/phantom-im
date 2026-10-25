import { useEffect, useState, type ReactNode } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { currentAccount, getRuntimeConfig } from "../services/api";
import { useAppStore } from "../store/app-store";
import { LoginPage } from "../features/auth/LoginPage";
import { RegisterPage } from "../features/auth/RegisterPage";
import { AppShell } from "./AppShell";

function Protected({ children }: { children: ReactNode }) {
  const account = useAppStore((state) => state.account);
  const setAccount = useAppStore((state) => state.setAccount);
  const [checking, setChecking] = useState(!account);
  const location = useLocation();

  useEffect(() => {
    if (account) return;
    currentAccount()
      .then(setAccount)
      .catch(() => setAccount(null))
      .finally(() => setChecking(false));
  }, [account, setAccount]);

  if (checking) return <div className="center-state">正在恢复登录状态…</div>;
  if (!account) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}

export function App() {
  const runtime = useAppStore((state) => state.runtime);
  const setRuntime = useAppStore((state) => state.setRuntime);
  const [error, setError] = useState("");

  useEffect(() => {
    getRuntimeConfig().then(setRuntime).catch((reason: Error) => setError(reason.message));
  }, [setRuntime]);

  if (error) return <div className="center-state error-state">运行配置加载失败：{error}</div>;
  if (!runtime) return <div className="center-state">正在加载 Phantom Web IM…</div>;

  return (
    <Routes>
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/*" element={<Protected><AppShell /></Protected>} />
    </Routes>
  );
}
