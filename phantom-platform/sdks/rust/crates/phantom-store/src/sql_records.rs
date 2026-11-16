use super::sqlite::{db, storage};
use phantom_domain::*;
use rusqlite::{Connection, Transaction, params};
use serde::{Serialize, de::DeserializeOwned};

pub(crate) const TABLES: &[&str] = &[
    "messages",
    "conversations",
    "drafts",
    "inbox",
    "outbox",
    "message_tombstones",
    "conversation_tombstones",
    "display_deadlines",
    "projections",
];
pub(crate) fn json(value: &impl Serialize) -> SdkResult<String> {
    serde_json::to_string(value).map_err(|_| storage("存储记录无法序列化"))
}
fn decode<T: DeserializeOwned>(value: String) -> SdkResult<T> {
    serde_json::from_str(&value).map_err(|_| storage("账号缓存记录损坏"))
}
fn all<T: DeserializeOwned>(conn: &Connection, table: &str) -> SdkResult<Vec<T>> {
    let mut stmt = db(conn.prepare(&format!("SELECT data FROM {table}")))?;
    let rows = db(stmt.query_map([], |row| row.get::<_, String>(0)))?;
    rows.map(|row| decode(db(row)?)).collect()
}
pub(crate) fn load(conn: &Connection) -> SdkResult<StorageSnapshot> {
    let (revision, user_seq) = db(conn.query_row(
        "SELECT revision,user_seq FROM meta WHERE singleton=1",
        [],
        |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
    ))?;
    let mut stmt = db(conn.prepare("SELECT key,data,invalidated FROM projections"))?;
    let rows = db(stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, Option<String>>(1)?,
            row.get::<_, bool>(2)?,
        ))
    }))?;
    let mut projections = Vec::new();
    let mut invalidated = Vec::new();
    for row in rows {
        let (key, data, invalid) = db(row)?;
        if let Some(data) = data {
            projections.push(decode(data)?);
        }
        if invalid {
            invalidated.push(decode(key)?);
        }
    }
    Ok(StorageSnapshot {
        revision: revision
            .parse()
            .map_err(|_| storage("缓存 revision 损坏"))?,
        user_seq,
        messages: all(conn, "messages")?,
        conversations: all(conn, "conversations")?,
        drafts: all(conn, "drafts")?,
        inbox: all(conn, "inbox")?,
        outbox: all(conn, "outbox")?,
        message_tombstones: all(conn, "message_tombstones")?,
        conversation_tombstones: all(conn, "conversation_tombstones")?,
        display_deadlines: all(conn, "display_deadlines")?,
        projections,
        invalidated,
    })
}
pub(crate) fn create_schema(tx: &Transaction<'_>) -> SdkResult<()> {
    db(tx.execute_batch("CREATE TABLE meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1),namespace TEXT NOT NULL,revision TEXT NOT NULL,user_seq INTEGER NOT NULL CHECK(user_seq>=0));
CREATE TABLE messages(client_msg_id TEXT PRIMARY KEY,msg_id TEXT UNIQUE,conv_id TEXT NOT NULL,conv_seq INTEGER CHECK(conv_seq IS NULL OR conv_seq>=0),data TEXT NOT NULL);
CREATE INDEX messages_conversation_sequence ON messages(conv_id,conv_seq);
CREATE TABLE conversations(conv_id TEXT PRIMARY KEY,data TEXT NOT NULL);
CREATE TABLE drafts(conv_id TEXT PRIMARY KEY,data TEXT NOT NULL);
CREATE TABLE inbox(user_seq INTEGER PRIMARY KEY CHECK(user_seq>=0),data TEXT NOT NULL);
CREATE TABLE outbox(client_msg_id TEXT PRIMARY KEY,data TEXT NOT NULL);
CREATE TABLE message_tombstones(msg_id TEXT PRIMARY KEY,data TEXT NOT NULL);
CREATE TABLE conversation_tombstones(conv_id TEXT PRIMARY KEY,data TEXT NOT NULL);
CREATE TABLE display_deadlines(msg_id TEXT PRIMARY KEY,data TEXT NOT NULL);
CREATE TABLE projections(key TEXT PRIMARY KEY,data TEXT,invalidated INTEGER NOT NULL DEFAULT 0);
"))?;
    Ok(())
}
fn put(
    tx: &Transaction<'_>,
    table: &str,
    column: &str,
    key: &impl rusqlite::ToSql,
    value: &impl Serialize,
) -> SdkResult<()> {
    db(tx.execute(&format!("INSERT INTO {table}({column},data) VALUES(?1,?2) ON CONFLICT({column}) DO UPDATE SET data=excluded.data"),params![key,json(value)?]))?;
    Ok(())
}
fn remove(tx: &Transaction<'_>, table: &str, column: &str, key: &str) -> SdkResult<()> {
    db(tx.execute(&format!("DELETE FROM {table} WHERE {column}=?1"), [key]))?;
    Ok(())
}
fn message(tx: &Transaction<'_>, value: &ChatMessage) -> SdkResult<()> {
    db(tx.execute("INSERT INTO messages(client_msg_id,msg_id,conv_id,conv_seq,data) VALUES(?1,?2,?3,?4,?5) ON CONFLICT(client_msg_id) DO UPDATE SET msg_id=excluded.msg_id,conv_id=excluded.conv_id,conv_seq=excluded.conv_seq,data=excluded.data",params![value.client_msg_id,value.msg_id,value.conv_id,value.conv_seq,json(value)?]))?;
    Ok(())
}
fn projection(tx: &Transaction<'_>, value: &DomainProjection) -> SdkResult<()> {
    db(tx.execute("INSERT INTO projections(key,data,invalidated) VALUES(?1,?2,0) ON CONFLICT(key) DO UPDATE SET data=excluded.data,invalidated=0",params![json(&value.key())?,json(value)?]))?;
    Ok(())
}
fn invalidate(tx: &Transaction<'_>, key: &ProjectionKey) -> SdkResult<()> {
    db(tx.execute("INSERT INTO projections(key,data,invalidated) VALUES(?1,NULL,1) ON CONFLICT(key) DO UPDATE SET invalidated=1",[json(key)?]))?;
    Ok(())
}
pub(crate) fn clear(tx: &Transaction<'_>) -> SdkResult<()> {
    for table in TABLES {
        db(tx.execute(&format!("DELETE FROM {table}"), []))?;
    }
    Ok(())
}
pub(crate) fn write_snapshot(tx: &Transaction<'_>, state: &StorageSnapshot) -> SdkResult<()> {
    for v in &state.messages {
        message(tx, v)?;
    }
    for v in &state.conversations {
        put(tx, "conversations", "conv_id", &v.conv_id, v)?;
    }
    for v in &state.drafts {
        put(tx, "drafts", "conv_id", &v.conv_id, v)?;
    }
    for v in &state.inbox {
        put(tx, "inbox", "user_seq", &v.user_seq, v)?;
    }
    for v in &state.outbox {
        put(tx, "outbox", "client_msg_id", &v.client_msg_id, v)?;
    }
    for v in &state.message_tombstones {
        put(tx, "message_tombstones", "msg_id", &v.msg_id, v)?;
    }
    for v in &state.conversation_tombstones {
        put(tx, "conversation_tombstones", "conv_id", &v.conv_id, v)?;
    }
    for v in &state.display_deadlines {
        put(tx, "display_deadlines", "msg_id", &v.msg_id, v)?;
    }
    for v in &state.projections {
        projection(tx, v)?;
    }
    for key in &state.invalidated {
        invalidate(tx, key)?;
    }
    Ok(())
}
pub(crate) fn mutations(tx: &Transaction<'_>, mutations: &[StorageMutation]) -> SdkResult<()> {
    for change in mutations {
        match change {
            StorageMutation::PutMessage(v) => message(tx, v)?,
            StorageMutation::RemoveMessage { client_msg_id } => {
                remove(tx, "messages", "client_msg_id", client_msg_id)?
            }
            StorageMutation::PutConversation(v) => {
                put(tx, "conversations", "conv_id", &v.conv_id, v)?
            }
            StorageMutation::RemoveConversation { conv_id } => {
                remove(tx, "conversations", "conv_id", conv_id)?
            }
            StorageMutation::PutDraft(v) => put(tx, "drafts", "conv_id", &v.conv_id, v)?,
            StorageMutation::PutInbox(v) => put(tx, "inbox", "user_seq", &v.user_seq, v)?,
            StorageMutation::PutOutbox(v) => {
                put(tx, "outbox", "client_msg_id", &v.client_msg_id, v)?
            }
            StorageMutation::RemoveOutbox { client_msg_id } => {
                remove(tx, "outbox", "client_msg_id", client_msg_id)?
            }
            StorageMutation::PutMessageTombstone(v) => {
                put(tx, "message_tombstones", "msg_id", &v.msg_id, v)?
            }
            StorageMutation::PutConversationTombstone(v) => {
                put(tx, "conversation_tombstones", "conv_id", &v.conv_id, v)?
            }
            StorageMutation::PutDisplayDeadline(v) => {
                put(tx, "display_deadlines", "msg_id", &v.msg_id, v)?
            }
            StorageMutation::RemoveDisplayDeadline { msg_id } => {
                remove(tx, "display_deadlines", "msg_id", msg_id)?
            }
            StorageMutation::PutProjection(v) => projection(tx, v)?,
            StorageMutation::InvalidateProjection(key) => invalidate(tx, key)?,
            StorageMutation::SetUserSeq(_) => {}
            StorageMutation::ReplaceState(state) => {
                clear(tx)?;
                write_snapshot(tx, state)?;
            }
        }
    }
    Ok(())
}
pub(crate) fn query_messages(
    conn: &Connection,
    query: MessageCacheQuery,
) -> SdkResult<Vec<ChatMessage>> {
    query.validate()?;
    let mut stmt=db(conn.prepare("SELECT data FROM messages WHERE conv_id=?1 AND (?2 IS NULL OR conv_seq<?2) ORDER BY (conv_seq IS NULL) DESC,conv_seq DESC,client_msg_id DESC LIMIT ?3"))?;
    let rows = db(stmt.query_map(
        params![query.conv_id, query.before_conv_seq, query.limit],
        |row| row.get::<_, String>(0),
    ))?;
    let mut values = rows
        .map(|row| decode(db(row)?))
        .collect::<SdkResult<Vec<_>>>()?;
    values.reverse();
    Ok(values)
}
pub(crate) fn conversation(conn: &Connection, id: &str) -> SdkResult<Option<Conversation>> {
    use rusqlite::OptionalExtension;
    db(conn
        .query_row(
            "SELECT data FROM conversations WHERE conv_id=?1",
            [id],
            |row| row.get::<_, String>(0),
        )
        .optional())?
    .map(decode)
    .transpose()
}
pub(crate) fn get_projection(
    conn: &Connection,
    key: &ProjectionKey,
) -> SdkResult<Option<DomainProjection>> {
    use rusqlite::OptionalExtension;
    db(conn
        .query_row(
            "SELECT data FROM projections WHERE key=?1 AND invalidated=0 AND data IS NOT NULL",
            [json(key)?],
            |row| row.get::<_, String>(0),
        )
        .optional())?
    .map(decode)
    .transpose()
}
