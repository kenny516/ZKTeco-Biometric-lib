package com.zkteco.commands;

public final class UserWriteResult {
    private final int assignedUid;
    private final boolean created;
    private final UserOperationStatus status;
    private final ZKCommandReply reply;
    private final String message;

    public UserWriteResult(int assignedUid, boolean created, UserOperationStatus status,
            ZKCommandReply reply, String message) {
        this.assignedUid = assignedUid;
        this.created = created;
        this.status = status;
        this.reply = reply;
        this.message = message;
    }

    public int getAssignedUid() { return assignedUid; }
    public boolean isCreated() { return created; }
    public UserOperationStatus getStatus() { return status; }
    public ZKCommandReply getReply() { return reply; }
    public String getMessage() { return message; }
    public boolean isSuccess() { return status == UserOperationStatus.SUCCESS; }
}
