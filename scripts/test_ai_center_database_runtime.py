#!/usr/bin/env python3
"""Run production onConfigure against strict Android-style APIs and the real SQLite engine."""
from pathlib import Path
import json,sqlite3,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
if not (ROOT/'patches').exists(): ROOT=Path('/workspace/Telegram-Premium')
source=(ROOT/'patches/tg13/overlay/TMessagesProj/src/main/java/org/telegram/messenger/telepremium/aicenter/CenterStore.java').read_text()
start=source.index('public void onConfigure(');opening=source.index('{',start);depth=1;end=opening+1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
method=source[start:end]
engine="import sys,sqlite3,json; db=sqlite3.connect(sys.argv[1]); db.execute('PRAGMA secure_delete=OFF'); cur=db.execute(sys.argv[2]); rows=cur.fetchall() if cur.description else None; db.commit(); print(json.dumps(rows)); db.close()"
with tempfile.TemporaryDirectory(prefix='center-sqlite-runtime-') as directory:
    root=Path(directory);database=root/'existing.db'
    connection=sqlite3.connect(database);connection.execute('CREATE TABLE records(id TEXT,value BLOB)');connection.execute("INSERT INTO records VALUES('existing',X'0001FF')");connection.commit();connection.close()
    files={
      'android/database/Cursor.java':'package android.database; public interface Cursor extends AutoCloseable {boolean moveToFirst();int getInt(int index);void close();}',
      'android/database/sqlite/SQLiteException.java':'package android.database.sqlite; public class SQLiteException extends RuntimeException {public SQLiteException(String message){super(message);}}',
      'android/database/sqlite/SQLiteDatabase.java':'''package android.database.sqlite;
import android.database.Cursor;import org.json.*;import java.nio.charset.StandardCharsets;
public class SQLiteDatabase {
 public final String path;public int queries,updates;public boolean closed;public int forced=-1;
 public SQLiteDatabase(String path){this.path=path;}
 private JSONArray execute(String sql){try{Process process=new ProcessBuilder("python3","-c",ENGINE,path,sql).start();String out=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(process.waitFor()!=0)throw new SQLiteException("SQLite engine failed");return out.equals("null")?null:new JSONArray(out);}catch(Exception failure){throw new SQLiteException(failure.toString());}}
 public void execSQL(String sql){updates++;if(execute(sql)!=null)throw new SQLiteException("Queries can be performed using SQLiteDatabase query or rawQuery methods only");}
 public Cursor rawQuery(String sql,String[] args){queries++;return new Cursor(){JSONArray rows;boolean positioned;public boolean moveToFirst(){rows=forced<0?execute(sql):new JSONArray().put(new JSONArray().put(forced));positioned=rows!=null&&rows.length()>0;return positioned;}public int getInt(int index){if(!positioned)throw new SQLiteException("Lazy query was not executed");return ((JSONArray)rows.opt(0)).optInt(index);}public void close(){closed=true;}};}
 private static final String ENGINE=ENGINE_LITERAL;
}'''.replace('ENGINE_LITERAL',json.dumps(engine)),
      'ConfigureProbe.java':'import android.database.Cursor;import android.database.sqlite.*;public final class ConfigureProbe {'+method+'}',
      'DatabaseRuntimeTest.java':'''import android.database.sqlite.*;public class DatabaseRuntimeTest {
 public static void main(String[] args){SQLiteDatabase oldApi=new SQLiteDatabase(args[0]);try{oldApi.execSQL("PRAGMA secure_delete=ON");throw new AssertionError("Original crash not reproduced");}catch(SQLiteException expected){}
 SQLiteDatabase db=new SQLiteDatabase(args[0]);new ConfigureProbe().onConfigure(db);if(db.queries!=1||db.updates!=0||!db.closed)throw new AssertionError("Correct query path/cursor lifecycle not used");SQLiteDatabase failed=new SQLiteDatabase(args[0]);failed.forced=0;try{new ConfigureProbe().onConfigure(failed);throw new AssertionError("Failed secure deletion silently accepted");}catch(SQLiteException expected){}if(!failed.closed)throw new AssertionError("Failure leaked cursor");System.out.println("CenterDatabaseRuntimeTest OK: reproduced OEM query rejection, production configure succeeds, lazy query executes, cursor closes and existing data survives");}}
''',
      'Compile.java':'import javax.tools.ToolProvider;class Compile{public static void main(String[]args){System.exit(ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,args));}}'
    }
    for name,contents in files.items():p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(contents)
    sources=[str(root/name) for name in files if name!='Compile.java']+[str(p) for p in (ROOT/'scripts/tests/stubs/org/json').glob('*.java')]
    subprocess.run(['java',str(root/'Compile.java'),'-d',str(root/'classes'),*sources],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'DatabaseRuntimeTest',str(database)],check=True)
    connection=sqlite3.connect(database);assert connection.execute("SELECT hex(value) FROM records WHERE id='existing'").fetchone()==('0001FF',);connection.close()
