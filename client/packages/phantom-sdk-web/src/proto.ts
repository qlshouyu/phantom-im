// proto 装载层：从预编译 JSON 描述符构建 protobuf Root（浏览器/Node 同构，无需运行时 fetch）。
import protobuf from "protobufjs";
import bundle from "./proto-bundle.json";

export const root = protobuf.Root.fromJSON(bundle as protobuf.INamespace);

export const Envelope = root.lookupType("phantom.v1.Envelope");
export const Command = root.lookupEnum("phantom.v1.Command").values;

export const AuthReq = root.lookupType("phantom.v1.AuthReq");
export const AuthResp = root.lookupType("phantom.v1.AuthResp");
export const Ping = root.lookupType("phantom.v1.Ping");
export const Pong = root.lookupType("phantom.v1.Pong");
export const SendMsgReq = root.lookupType("phantom.v1.SendMsgReq");
export const SendMsgResp = root.lookupType("phantom.v1.SendMsgResp");
export const MsgPush = root.lookupType("phantom.v1.MsgPush");
export const MsgAck = root.lookupType("phantom.v1.MsgAck");
export const MsgContent = root.lookupType("phantom.v1.MsgContent");
export const SyncReq = root.lookupType("phantom.v1.SyncReq");
export const SyncResp = root.lookupType("phantom.v1.SyncResp");
export const KickPush = root.lookupType("phantom.v1.KickPush");

/** 单聊 conv_id 规则（与服务端 ConvIds 一致：si_ + 双方 uid 升序拼接）。 */
export function singleConvId(a: string, b: string): string {
  return "si_" + [a, b].sort().join("__");
}
