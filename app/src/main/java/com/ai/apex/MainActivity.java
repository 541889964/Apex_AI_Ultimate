package com.ai.apex;
import android.app.AlertDialog;
import android.content.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private DrawerLayout drawer;
    private RecyclerView rvChat;
    private MyAdapter adapter;
    private List<String[]> msgs = new ArrayList<>();
    private EditText etMsg, etKey;
    private TextView tvStatus, tvTerminal, btnModeAI, btnModeTerminal;
    private Spinner spinnerMode, spinnerModel;
    private Switch swSpeak;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).build();
    private TextToSpeech tts;
    private boolean isTerminal = false;
    private int permMode = 1; // 1 Root, 2 Shizuku, 0 Normal
    private String model = "deepseek-chat";
    private int loop = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("Apex", MODE_PRIVATE);
        
        drawer = findViewById(R.id.drawer);
        rvChat = findViewById(R.id.rvChat);
        etMsg = findViewById(R.id.etMsg);
        etKey = findViewById(R.id.etKey);
        tvStatus = findViewById(R.id.tvStatus);
        tvTerminal = findViewById(R.id.tvTerminal);
        btnModeAI = findViewById(R.id.btnModeAI);
        btnModeTerminal = findViewById(R.id.btnModeTerminal);
        spinnerMode = findViewById(R.id.spinnerMode);
        spinnerModel = findViewById(R.id.spinnerModel);
        swSpeak = findViewById(R.id.swSpeak);

        tts = new TextToSpeech(this, s -> { if(s == TextToSpeech.SUCCESS) tts.setLanguage(Locale.CHINESE); });
        adapter = new MyAdapter(msgs);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(adapter);

        etKey.setText(sp.getString("key", ""));
        addMsg("系统", "Apex 旗舰版已就绪。请配置左侧 API Key。");

        String[] modes = {"普通沙盒", "ROOT 权限", "SHIZUKU 模式"};
        spinnerMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        spinnerMode.setSelection(1);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { permMode = pos; }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] models = {"V3 极速", "R1 深度"};
        spinnerModel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models));
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { model = (pos == 0) ? "deepseek-chat" : "deepseek-reasoner"; }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        findViewById(R.id.btnMenu).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.btnSaveKey).setOnClickListener(v -> { sp.edit().putString("key", etKey.getText().toString()).apply(); Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); drawer.closeDrawer(GravityCompat.START); });

        // 快捷键
        findViewById(R.id.btnLogcat).setOnClickListener(v -> runCmd("logcat -d -t 100", false));
        findViewById(R.id.btnApps).setOnClickListener(v -> runCmd("pm list packages -3", false));
        findViewById(R.id.btnClean).setOnClickListener(v -> runCmd("sync; echo 3 > /proc/sys/vm/drop_caches", false));

        // 模式切换
        btnModeAI.setOnClickListener(v -> {
            isTerminal = false;
            findViewById(R.id.layoutAI).setVisibility(View.VISIBLE);
            findViewById(R.id.layoutTerminal).setVisibility(View.GONE);
            btnModeAI.setTextColor(0xFF00E5FF); btnModeTerminal.setTextColor(0xFF64748B);
            etMsg.setHint("输入指令...");
        });
        btnModeTerminal.setOnClickListener(v -> {
            isTerminal = true;
            findViewById(R.id.layoutAI).setVisibility(View.GONE);
            findViewById(R.id.layoutTerminal).setVisibility(View.VISIBLE);
            btnModeTerminal.setTextColor(0xFF00E5FF); btnModeAI.setTextColor(0xFF64748B);
            etMsg.setHint("输入 Linux 命令...");
        });

        // 语音输入
        findViewById(R.id.btnMic).setOnClickListener(v -> {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            startActivityForResult(i, 100);
        });

        // 发送
        findViewById(R.id.btnSend).setOnClickListener(v -> {
            String txt = etMsg.getText().toString().trim();
            if(txt.isEmpty()) return;
            etMsg.setText("");
            if(isTerminal) {
                runCmd(txt, false);
            } else {
                if(sp.getString("key", "").isEmpty()) { Toast.makeText(this, "请配置Key", Toast.LENGTH_SHORT).show(); return; }
                addMsg("我", txt);
                loop = 0;
                callAPI(txt, null, false);
            }
        });
    }

    @Override protected void onActivityResult(int req, int res, @Nullable Intent d) {
        super.onActivityResult(req, res, d);
        if(req==100 && res==RESULT_OK && d!=null) {
            ArrayList<String> r = d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(r != null && !r.isEmpty()) etMsg.setText(r.get(0));
        }
    }

    private void addMsg(String role, String txt) {
        List<String[]> n = new ArrayList<>(msgs);
        n.add(new String[]{role, txt});
        adapter.update(n);
        rvChat.scrollToPosition(n.size()-1);
    }

    private void updateLast(String add) {
        if(msgs.isEmpty()) return;
        String[] last = msgs.get(msgs.size()-1);
        if(last[0].equals("AI") || last[0].equals("系统")) {
            last[1] += add;
            adapter.update(new ArrayList<>(msgs));
            rvChat.scrollToPosition(msgs.size()-1);
        }
    }

    private void callAPI(String q, String res, boolean isLoop) {
        if(!isLoop) addMsg("AI", "分析中...\n");
        ui.post(() -> tvStatus.setText("思考中..."));
        try {
            JSONObject j = new JSONObject();
            j.put("model", model); j.put("stream", true);
            JSONArray a = new JSONArray();
            a.put(new JSONObject().put("role", "system").put("content", "你是安卓AI。执行命令用：[EXEC] 命令。未完成禁止输出[TASK_DONE]。"));
            a.put(new JSONObject().put("role", "user").put("content", q));
            if(isLoop && res!=null) a.put(new JSONObject().put("role", "assistant").put("content", "反馈：\n" + res));
            j.put("messages", a);

            Request req = new Request.Builder().url("https://api.deepseek.com/v1/chat/completions")
                .header("Authorization", "Bearer " + sp.getString("key",""))
                .post(RequestBody.create(j.toString(), MediaType.parse("application/json"))).build();

            client.newCall(req).enqueue(new Callback() {
                public void onFailure(Call c, IOException e) { ui.post(() -> tvStatus.setText("断网")); }
                public void onResponse(Call c, Response r) {
                    if(r.body()==null) return;
                    try(BufferedReader rd = new BufferedReader(new InputStreamReader(r.body().byteStream()))) {
                        String l; StringBuilder full = new StringBuilder();
                        while((l=rd.readLine())!=null) {
                            if(l.startsWith("data: ") && !l.equals("data: [DONE]")) {
                                JSONObject ch = new JSONObject(l.substring(6));
                                JSONObject d = ch.getJSONArray("choices").getJSONObject(0).optJSONObject("delta");
                                if(d!=null && d.has("reasoning_content")) updateLast(d.getString("reasoning_content"));
                                if(d!=null && d.has("content") && !d.getString("content").isEmpty()) {
                                    updateLast(d.getString("content")); full.append(d.getString("content"));
                                }
                            }
                        }
                        ui.post(() -> {
                            tvStatus.setText("就绪");
                            if(swSpeak.isChecked()) tts.speak(full.toString(), TextToSpeech.QUEUE_FLUSH, null, null);
                        });
                        execAction(full.toString(), q);
                    } catch(Exception e) {}
                }
            });
        } catch(Exception e) {}
    }

    private void execAction(String rep, String oq) {
        if(rep.contains("[TASK_DONE]")) { addMsg("系统", "任务完成 ✅"); return; }
        if(rep.contains("[EXEC]")) {
            String cmd = rep.substring(rep.indexOf("[EXEC]")+6).split("\n")[0].replace("`","").trim();
            if(cmd.contains("rm -rf") || cmd.contains("reboot")) {
                ui.post(() -> new AlertDialog.Builder(this).setTitle("⚠️ 高危警告").setMessage("AI 试图执行：\n"+cmd)
                    .setPositiveButton("放行",(d,w)->runCmd(cmd,true))
                    .setNegativeButton("拒绝",(d,w)->callAPI(oq,"用户拒绝",true)).show());
                return;
            }
            runCmd(cmd, true);
        }
    }

    private void runCmd(String cmd, boolean isAI) {
        if(isTerminal) tvTerminal.append("\n$ " + cmd + "\n"); else addMsg("系统", "执行: " + cmd);
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Process p = null;
                if(permMode==1) p = Runtime.getRuntime().exec(new String[]{"su","-c",cmd});
                else if(permMode==2 && rikka.shizuku.Shizuku.pingBinder()) {
                    java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    m.setAccessible(true); p = (Process) m.invoke(null, new String[]{"sh","-c",cmd}, null, null);
                } else p = Runtime.getRuntime().exec(new String[]{"sh","-c",cmd});
                
                if(p!=null) {
                    BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String l; while((l=rd.readLine())!=null) out.append(l).append("\n");
                    p.waitFor();
                }
            } catch(Exception e) { out.append("错误: ").append(e.getMessage()); }
            
            String res = out.toString().trim();
            ui.post(() -> {
                if(isTerminal) tvTerminal.append(res + "\n$ ");
                else {
                    addMsg("系统", "结果:\n" + res);
                    if(isAI && loop<30) { loop++; callAPI(msgs.get(msgs.size()-2)[1], res, true); }
                }
            });
        }).start();
    }

    static class MyAdapter extends RecyclerView.Adapter<MyAdapter.VH> {
        List<String[]> data;
        MyAdapter(List<String[]> d){ data=d; }
        void update(List<String[]> n) {
            DiffUtil.DiffResult r = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                public int getOldListSize(){ return data.size(); }
                public int getNewListSize(){ return n.size(); }
                public boolean areItemsTheSame(int a,int b){ return true; }
                public boolean areContentsTheSame(int a,int b){ return data.get(a)[1].equals(n.get(b)[1]); }
            });
            data.clear(); data.addAll(n); r.dispatchUpdatesTo(this);
        }
        @NonNull public VH onCreateViewHolder(@NonNull ViewGroup p, int t){ return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_message,p,false)); }
        public void onBindViewHolder(@NonNull VH h, int pos) {
            String[] m = data.get(pos);
            boolean isUser = m[0].equals("我");
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.tv.getLayoutParams();
            lp.gravity = isUser ? android.view.Gravity.END : android.view.Gravity.START;
            h.tv.setLayoutParams(lp);
            h.tv.setBackgroundResource(isUser ? R.drawable.bubble_user : R.drawable.bubble_ai);
            h.tv.setText(m[1]);
            h.tv.setTextColor(isUser ? 0xFF000000 : 0xFFE2E8F0);
        }
        public int getItemCount(){ return data.size(); }
        static class VH extends RecyclerView.ViewHolder { TextView tv; VH(View v){ super(v); tv=v.findViewById(R.id.tvContent); } }
    }
}
