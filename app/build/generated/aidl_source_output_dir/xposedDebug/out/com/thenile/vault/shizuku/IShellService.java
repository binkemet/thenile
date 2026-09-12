/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\\Users\\londh\\AppData\\Local\\Android\\Sdk\\build-tools\\36.0.0\\aidl.exe -pC:\\Users\\londh\\AppData\\Local\\Android\\Sdk\\platforms\\android-36\\framework.aidl -oD:\\thenile\\app\\build\\generated\\aidl_source_output_dir\\xposedDebug\\out -ID:\\thenile\\app\\src\\main\\aidl -ID:\\thenile\\app\\src\\xposed\\aidl -ID:\\thenile\\app\\src\\debug\\aidl -ID:\\thenile\\app\\src\\xposedDebug\\aidl -IC:\\Users\\londh\\.gradle\\caches\\9.1.0\\transforms\\3db2429b6fc5c44076d17a074f1c7700\\workspace\\transformed\\core-1.18.0\\aidl -IC:\\Users\\londh\\.gradle\\caches\\9.1.0\\transforms\\14301d7b5db1f9e5514acc6b2308d07e\\workspace\\transformed\\versionedparcelable-1.1.1\\aidl -dC:\\Users\\londh\\AppData\\Local\\Temp\\aidl12783371495517140893.d D:\\thenile\\app\\src\\main\\aidl\\com\\thenile\\vault\\shizuku\\IShellService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package com.thenile.vault.shizuku;
// Bound via Shizuku.bindUserService — runs in its own process at shell UID (2000), not Nile's
// app UID. One method rather than separate stdout/stderr/exitCode calls to keep this to a single
// round trip; ShizukuShell.exec() parses the trailing exit-code marker back out.
public interface IShellService extends android.os.IInterface
{
  /** Default implementation for IShellService. */
  public static class Default implements com.thenile.vault.shizuku.IShellService
  {
    @Override public java.lang.String exec(java.lang.String script) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.thenile.vault.shizuku.IShellService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.thenile.vault.shizuku.IShellService interface,
     * generating a proxy if needed.
     */
    public static com.thenile.vault.shizuku.IShellService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.thenile.vault.shizuku.IShellService))) {
        return ((com.thenile.vault.shizuku.IShellService)iin);
      }
      return new com.thenile.vault.shizuku.IShellService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_exec:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.exec(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.thenile.vault.shizuku.IShellService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public java.lang.String exec(java.lang.String script) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(script);
          boolean _status = mRemote.transact(Stub.TRANSACTION_exec, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_exec = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.thenile.vault.shizuku.IShellService";
  public java.lang.String exec(java.lang.String script) throws android.os.RemoteException;
}
