package org.ligi.passandroid.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActionBarDrawerToggle;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.view.ActionMode;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.androidquery.service.MarketService;
import com.squareup.otto.Subscribe;

import org.ligi.passandroid.App;
import org.ligi.passandroid.R;
import org.ligi.passandroid.Tracker;
import org.ligi.passandroid.events.NavigationOpenedEvent;
import org.ligi.passandroid.events.PassbookUpdatedEvent;
import org.ligi.passandroid.events.SortOrderChangeEvent;
import org.ligi.passandroid.events.TypeFocusEvent;
import org.ligi.passandroid.helper.PassVisualizer;
import org.ligi.passandroid.model.Passbook;
import org.ligi.tracedroid.TraceDroid;
import org.ligi.tracedroid.logging.Log;
import org.ligi.tracedroid.sending.TraceDroidEmailSender;

import java.io.File;
import java.io.InputStream;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnItemClick;
import uk.co.senab.actionbarpulltorefresh.extras.actionbarcompat.PullToRefreshLayout;
import uk.co.senab.actionbarpulltorefresh.library.ActionBarPullToRefresh;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;

import static org.ligi.passandroid.ui.UnzipPassController.SilentFail;

public class TicketListActivity extends ActionBarActivity {

    private LayoutInflater inflater;
    private PassAdapter passadapter;
    private boolean scanning = false;

    private ScanForPassesTask scan_task = null;
    private ActionBarDrawerToggle drawerToggle;

    @InjectView(R.id.content_list)
    ListView listView;

    @InjectView(R.id.drawer_layout)
    DrawerLayout drawer;

    @InjectView(R.id.emptyView)
    TextView emptyView;

    @InjectView(R.id.ptr_layout)
    PullToRefreshLayout mPullToRefreshLayout;

    private ActionMode actionMode;

    @Subscribe
    public void sortOrderChange(SortOrderChangeEvent orderChangeEvent) {
        refreshPasses();
    }

    @Subscribe
    public void typeFocus(TypeFocusEvent typeFocusEvent) {
        scrollToType(typeFocusEvent.type);
        drawer.closeDrawers();
    }

    @Subscribe public void passbookUpdated(PassbookUpdatedEvent event) {
        passadapter = new PassAdapter();
        listView.setAdapter(passadapter);
    }

    @OnItemClick(R.id.content_list)
    void lisItemCick(int position) {
        Intent intent = new Intent(TicketListActivity.this, TicketViewActivity.class);
        intent.putExtra("path", App.getPassStore().getPassbookAt(position).getPath());
        startActivity(intent);
    }

    public void refreshPasses() {
        App.getPassStore().refreshPassesList();
        App.getPassStore().sort(App.getSettings().getSortOrder());
        passadapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.ticket_list);
        ButterKnife.inject(this);

        passadapter = new PassAdapter();
        listView.setAdapter(passadapter);

        inflater = getLayoutInflater();

        listView.setEmptyView(emptyView);

        // don't want too many windows in worst case - so check for errors first
        if (TraceDroid.getStackTraceFiles().length > 0) {
            Tracker.get().trackEvent("ui_event", "send", "stacktraces", null);
            TraceDroidEmailSender.sendStackTraces("ligi@ligi.de", this);
        } else { // if no error - check if there is a new version of the app
            Tracker.get().trackEvent("ui_event", "processInputStream", "updatenotice", null);
            MarketService ms = new MarketService(this);
            ms.level(MarketService.MINOR).checkVersion();
        }

        drawerToggle = new ActionBarDrawerToggle(this, drawer, R.drawable.ic_drawer, R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerOpened(View drawerView) {
                App.getBus().post(new NavigationOpenedEvent());
            }
        };
        drawer.setDrawerListener(drawerToggle);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(final AdapterView<?> parent, final View view, final int position, long id) {

                listView.setItemChecked(position, true);
                view.refreshDrawableState();

                if (actionMode != null) {
                    // no need to restart -> NTFS restarting is even dangerous as this deselects the item
                    return true;
                }

                actionMode = startSupportActionMode(new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {

                        getMenuInflater().inflate(R.menu.activity_ticket_view, menu);
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                        return false;
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                        if (new PassMenuOptions(TicketListActivity.this, App.getPassStore().getPassbookAt(position)).process(menuItem)) {
                            actionMode.finish();
                            return true;
                        }

                        return false;
                    }

                    @Override
                    public void onDestroyActionMode(ActionMode _actionMode) {
                        listView.setItemChecked(position, false);
                        actionMode = null;
                    }
                });

                return true;

            }
        });

        ActionBarPullToRefresh.from(this)
                .allChildrenArePullable()
                .listener(new OnRefreshListener() {
                    @Override
                    public void onRefreshStarted(View view) {
                        App.getPassStore().deleteCache();
                        new ScanForPassesTask().execute();
                    }
                })

                .setup(mPullToRefreshLayout);
    }

    private void scrollToType(String type) {
        for (int i = 0; i < passadapter.getCount(); i++) {
            if (App.getPassStore().getReducedPassbookAt(i).getTypeNotNull().equals(type)) {
                listView.setSelection(i);
                return; // we are done
            }
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        switch (item.getItemId()) {
            case R.id.menu_refresh:
                Tracker.get().trackEvent("ui_event", "refresh", "from_list", null);
                new ScanForPassesTask().execute();
                return true;
            case R.id.menu_help:
                Intent intent = new Intent(this, HelpActivity.class);
                startActivity(intent);
                return true;

        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (App.getPassStore().isEmpty()) {
            scan_task = new ScanForPassesTask();
            scan_task.execute();
        }

        updateUIToScanningState();

        Tracker.get().trackEvent("ui_event", "resume", "passes", (long) App.getPassStore().passCount());

        App.getBus().register(this);


    }


    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        if (!scanning) {
            getMenuInflater().inflate(R.menu.activity_ticket_list_view, menu);
        }
        return true;
    }

    public void updateUIToScanningState() {

        mPullToRefreshLayout.setRefreshing(scanning);

        supportInvalidateOptionsMenu();

        String html;
        if (scanning) {
            html = getString(R.string.scan_empty_text);
        } else {
            html = getString(R.string.no_passes_empty_text);
        }

        emptyView.setText(Html.fromHtml(html));

        emptyView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }


    @Override
    protected void onPause() {
        App.getBus().unregister(this);

        if (scan_task != null) {
            scan_task.cancel(true);
        }
        scanning = false;
        super.onPause();
    }

    class ImportAndRefreshListAsync extends ImportAsyncTask {

        public ImportAndRefreshListAsync(Activity ticketImportActivity, Uri intent_uri) {
            super(ticketImportActivity, intent_uri);
        }

        @Override
        protected InputStream doInBackground(Void... params) {
            InputStream ins = super.doInBackground(params);
            UnzipPassController.processInputStream(ins, ticketImportActivity, new UnzipPassController.SuccessCallback() {
                @Override
                public void call(String pathToPassbook) {
                    Passbook passbook = new Passbook(pathToPassbook);
                    passbook.update(TicketListActivity.this);
                }
            }, new SilentFail());
            return ins;
        }

        @Override
        protected void onPostExecute(InputStream result) {
            refreshPasses();
            super.onPostExecute(result);
        }
    }

    class ScanForPassesTask extends AsyncTask<Void, Void, Void> {

        long start_time;

        /**
         * recursive traversion from path on to find files named .pkpass
         *
         * @param path
         */
        private void search_in(String path) {

            if (path == null) {
                Log.w("trying to search in null path");
                return;
            }

            File dir = new File(path);
            File[] files = dir.listFiles();

            if (files != null) for (File file : files) {
                if (file.isDirectory()) {
                    search_in(file.toString());
                } else if (file.getName().endsWith(".pkpass")) {
                    Log.i("found" + file.getAbsolutePath());
                    new ImportAndRefreshListAsync(TicketListActivity.this, Uri.parse("file://" + file.getAbsolutePath())).execute();
                }
            }

        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            scanning = true;

            start_time = System.currentTimeMillis();

            Tracker.get().trackEvent("ui_event", "scan", "started", null);

            updateUIToScanningState();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            scanning = false;

            // TODO bring back Tracker.get().trackTiming("timing", System.currentTimeMillis() - start_time, "scan", "scan_time");
            updateUIToScanningState();
        }

        @Override
        protected void onCancelled() {
            Tracker.get().trackEvent("ui_event", "scan", "cancelled", null);
            super.onCancelled();
        }

        @Override
        protected Void doInBackground(Void... params) {

            // note to future_me: yea one thinks we only need to search root here, but root was /system for me and so
            // did not contain "/SDCARD" #dontoptimize
            // on my phone:sdcard
            // search in /system
            // (26110): search in /mnt/
            // (26110): search in /cache
            // (26110): search in /data
            search_in(Environment.getRootDirectory().toString());
            search_in(Environment.getExternalStorageDirectory().toString());
            search_in(Environment.getDownloadCacheDirectory().toString());
            search_in(Environment.getDataDirectory().toString());
            return null;
        }
    }

    class PassAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return App.getPassStore().passCount();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            View res = inflater.inflate(R.layout.pass_list_item, null);

            PassVisualizer.visualize(TicketListActivity.this, App.getPassStore().getReducedPassbookAt(position), res);

            return res;
        }

    }

}
