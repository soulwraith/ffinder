package com.ffinder.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.Pair;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.Toolbar;
import android.view.*;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import com.ffinder.android.absint.activities.MyActivityAbstract;
import com.ffinder.android.absint.adapters.IFriendItemListener;
import com.ffinder.android.absint.controls.ISearchFailedListener;
import com.ffinder.android.absint.databases.FirebaseListener;
import com.ffinder.android.absint.models.MyModelChangedListener;
import com.ffinder.android.absint.tasks.RequestLocationTaskFragListener;
import com.ffinder.android.adapters.FriendsAdapter;
import com.ffinder.android.controls.*;
import com.ffinder.android.enums.*;
import com.ffinder.android.helpers.Analytics;
import com.ffinder.android.helpers.FirebaseDB;
import com.ffinder.android.models.FriendModel;
import com.ffinder.android.models.LocationModel;
import com.ffinder.android.models.MyModel;
import com.ffinder.android.statics.Vars;
import com.ffinder.android.tasks.AdsIdTask;
import com.ffinder.android.tasks.RequestLocationTaskFrag;
import com.ffinder.android.utils.PreferenceUtils;
import com.ffinder.android.utils.RunnableArgs;
import com.ffinder.android.utils.Strings;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.iid.FirebaseInstanceId;

import java.util.List;


public class ActivityMain extends MyActivityAbstract implements IFriendItemListener {

    private ActivityMain _this;
    private FragmentIdentity fragmentIdentity;
    private FragmentEmptyFriend fragmentEmptyFriend;
    private FragmentNextAdsCd fragmentNextAdsCd;
    private MyModel myModel;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ListView listFriends;
    private RelativeLayout layoutEmptyFriend;
    private FriendsAdapter friendsAdapter;
    private BroadcastReceiver receiver;
    private boolean afterSavedInstanceState;

    public ActivityMain() {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        super.onCreate(savedInstanceState);
        _this = this;
        setContentView(R.layout.activity_main);
        Toolbar myToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        myModel = new MyModel(this);
        myModel.loadAllFriendModels();
        myModel.sortFriendModels();
        myModel.loginFirebase(0, null);

        fragmentNextAdsCd = (FragmentNextAdsCd) getSupportFragmentManager().findFragmentById(R.id.nextAdsCdFragment);
        fragmentNextAdsCd.setMyModel(myModel);

        fragmentIdentity = (FragmentIdentity) getSupportFragmentManager().findFragmentById(R.id.identityFragment);
        fragmentIdentity.setMyModel(myModel);

        fragmentEmptyFriend = (FragmentEmptyFriend) getSupportFragmentManager().findFragmentById(R.id.emptyFriendFragment);
        fragmentEmptyFriend.setMyModel(myModel);

        swipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipeRefresh);
        swipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(this, R.color.colorPrimary));
        listFriends = (ListView) findViewById(R.id.listFriends);
        friendsAdapter = new FriendsAdapter(this, R.layout.lvitem_friend, myModel.getFriendModels(), myModel, this);
        listFriends.setAdapter(friendsAdapter);
        registerForContextMenu(listFriends);

        layoutEmptyFriend = (RelativeLayout) findViewById(R.id.layoutEmptyFriendFragment);

        setListeners();
        checkHasPendingToAddUser();
        recreateRequestLocationTaskFrags();

        AdsIdTask adsIdTask = new AdsIdTask(this, myModel.getUserId());
        adsIdTask.execute();

        String refreshedToken = FirebaseInstanceId.getInstance().getToken();
        if(!Strings.isEmpty(refreshedToken)) FirebaseDB.updateMyToken(myModel.getUserId(), refreshedToken);

        checkKnownIssuePhones();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.default_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()){
            case R.id.action_add:
                AddMemberDialog addMemberDialog = new AddMemberDialog(this, myModel);
                addMemberDialog.show();
                break;
            case R.id.action_settings:
                Intent intent = new Intent(this, ActivitySettings.class);
                startActivity(intent);
                break;
            case R.id.action_promo:
                Intent intent2 = new Intent(this, ActivityPromo.class);
                intent2.putExtra("userId", myModel.getUserId());
                startActivity(intent2);
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.setHeaderTitle("");
        menu.add(getString(R.string.edit_name_context_menu));
        menu.add(getString(R.string.delete_user_context_menu));

    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        int listPosition = info.position;

        if(item.getTitle().equals(getString(R.string.edit_name_context_menu))){
            editName(myModel.getFriendModels().get(listPosition));
        }
        else if(item.getTitle().equals(getString(R.string.delete_user_context_menu))){
            deleteUser(myModel.getFriendModels().get(listPosition));
        }else{
            return false;
        }
        return true;
    }

    private void checkHasPendingToAddUser(){
        if(!Strings.isEmpty(Vars.pendingAddUserKey)){
            AddMemberDialog addMemberDialog = new AddMemberDialog(this, myModel);
            addMemberDialog.show();
        }
    }

    private void recreateRequestLocationTaskFrags(){
        FragmentManager fm = getSupportFragmentManager();

        for(FriendModel friendModel : myModel.getFriendModels()){
            RequestLocationTaskFrag taskFragment = (RequestLocationTaskFrag) fm.findFragmentByTag(friendModel.getUserId());

            // If the Fragment is non-null, then it is currently being
            // retained across a configuration change.
            if (taskFragment != null) {
                friendModel.setSearchResult(SearchResult.Normal);
                friendModel.setSearchStatus(taskFragment.getCurrentStatus());
                setRequestLocationTaskFragListener(taskFragment);
            }
        }
    }

    private void createRequestLocationTaskFrag(final FriendModel friendModel){
        final FragmentManager fm = getSupportFragmentManager();
        final RequestLocationTaskFrag[] taskFragment = {(RequestLocationTaskFrag) fm.findFragmentByTag(friendModel.getUserId())};
        //not in search
        if (taskFragment[0] == null || taskFragment[0].getCurrentResult() != null) {
            if(friendModel.getSearchResult() != null){
                if(friendModel.getSearchResult() == SearchResult.ErrorTimeoutUnknownReason
                        || friendModel.getSearchResult() == SearchResult.ErrorTimeoutLocationDisabled){

                    new SearchFailedDialog(ActivityMain.this,
                            friendModel.getSearchResult(), new ISearchFailedListener() {
                        @Override
                        public void onSearchAnywayChoose() {
                            searchNow(friendModel);
                        }
                    }).show();
                    return;
                }
            }

            searchNow(friendModel);
        }
    }

    private void searchNow(final FriendModel friendModel){
        fragmentNextAdsCd.friendSearched(new RunnableArgs<Boolean>() {
            @Override
            public void run() {
                if(this.getFirstArg()){
                    friendModel.setSearchResult(SearchResult.Normal);
                    friendModel.setSearchStatus(SearchStatus.Starting);
                    final FragmentManager fm = getSupportFragmentManager();
                    RequestLocationTaskFrag frag =  RequestLocationTaskFrag.newInstance(myModel.getUserId(), friendModel.getUserId());
                    fm.beginTransaction().add(frag, friendModel.getUserId()).commit();
                    setRequestLocationTaskFragListener(frag);
                }
            }
        });
    }


    private void refreshFriendList(){
        FirebaseDB.getAllMyLinks(myModel.getUserId(), new FirebaseListener() {
            @Override
            public void onResult(Object result, Status status) {
                if(status == Status.Success && result != null){
                    List<Pair<String, Object>> list = (List<Pair<String, Object>>) result;
                    boolean foundNew = false;
                    for(Pair<String, Object> pair : list){
                        if(!myModel.checkFriendExist(pair.first)){
                            String userId = (String) pair.first;
                            String name = (String) pair.second;

                            FriendModel friendModel = new FriendModel();
                            friendModel.setName(name);
                            friendModel.setUserId(userId);
                            friendModel.save(_this);
                            myModel.addFriendModel(friendModel, false);
                            foundNew = true;
                        }
                    }

                    if(foundNew){;
                        myModel.sortFriendModels();
                        myModel.commitFriendUserIds();
                        myModel.notifyFriendModelsChanged();
                    }

                }
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                swipeRefreshLayout.setRefreshing(false);
            }
        }, 20 * 1000);
    }

    private void editName(FriendModel friendModel){
        EditNameDialog editNameDialog = new EditNameDialog(this, friendModel, myModel);
        editNameDialog.show();
    }

    private void deleteUser(FriendModel friendModel){
        ConfirmDeleteDialog confirmDeleteDialog = new ConfirmDeleteDialog(this, friendModel, myModel);
        confirmDeleteDialog.show();
    }

    private void checkKnownIssuePhones(){
        boolean notify = Strings.isEmpty(PreferenceUtils.get(this, PreferenceType.DontRemindMeAgainPhoneIssue));
        if(notify){
            String model = Build.BRAND;
            if(model.equalsIgnoreCase(PhoneBrand.Huawei.name())
                    || model.equalsIgnoreCase(PhoneBrand.Xiaomi.name())
                    || model.equalsIgnoreCase(PhoneBrand.Sony.name())){
                KnownIssueDialog knownIssueDialog = new KnownIssueDialog(this, PhoneBrand.valueOf(model));
                knownIssueDialog.show();
            }
        }
    }

    public void setListeners(){
        listFriends.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                createRequestLocationTaskFrag(myModel.getFriendModels().get(position));
            }
        });

        myModel.addMyModelChangedListener(new MyModelChangedListener() {
            @Override
            public void onChanged(MyModel newMyModel, String changedProperty) {
                if(changedProperty.equals("friendModels") && !isAfterSavedInstanceState()){
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            friendsAdapter.notifyDataSetChanged();

                            if(myModel.getFriendModels().size() == 0){
                                layoutEmptyFriend.setVisibility(View.VISIBLE);
                            }
                            else{
                                layoutEmptyFriend.setVisibility(View.GONE);
                            }

                        }
                    });
                }
            }
        });

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                refreshFriendList();
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction("REFRESH_FRIEND");

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String friendId = intent.getStringExtra("userId");
                if(myModel.checkFriendExist(friendId)) {
                    final FriendModel friendModel = myModel.getFriendModelById(friendId);
                    friendModel.load(_this);
                }
            }
        };
        registerReceiver(receiver, filter);

    }

    @Override
    public void onSearchRequest(FriendModel friendModel) {
        createRequestLocationTaskFrag(friendModel);
    }

    public void setRequestLocationTaskFragListener(final RequestLocationTaskFrag requestLocationTaskFrag){
        requestLocationTaskFrag.setRequestLocationTaskFragListener(new RequestLocationTaskFragListener() {
            @Override
            public void onUpdateStatus(String userId, SearchStatus newStatus) {
                FriendModel friendModel = myModel.getFriendModelById(userId);
                friendModel.setSearchStatus(newStatus);
            }

            @Override
            public void onUpdateResult(String userId, LocationModel locationModel, SearchStatus finalSearchStatus, SearchResult result) {
                FriendModel friendModel = myModel.getFriendModelById(userId);
                if(locationModel != null){
                    friendModel.setLastLocationModel(locationModel);
                }
                friendModel.setSearchStatus(finalSearchStatus);
                friendModel.setSearchResult(result);
                friendModel.save(_this);

                if(!isAfterSavedInstanceState()){
                    Fragment fragment = getSupportFragmentManager().findFragmentByTag(userId);
                    if(fragment != null)
                        getSupportFragmentManager().beginTransaction().remove(fragment).commit();
                }

                Analytics.logEvent(AnalyticEvent.Search_Result, result.name());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        afterSavedInstanceState = false;

        for(FriendModel friendModel : myModel.getFriendModels()){
            Fragment fragment = getSupportFragmentManager().findFragmentByTag(friendModel.getUserId());
            if(fragment != null){
                if(friendModel.getSearchStatus() == SearchStatus.End){
                    getSupportFragmentManager().beginTransaction().remove(fragment).commit();
                }
            }
        }

        if(friendsAdapter != null) friendsAdapter.notifyDataSetChanged();
        refreshFriendList();

        PreferenceUtils.delete(this, PreferenceType.AutoNotifiedReceivedIds);
    }

    @Override
    protected void onPause() {
        super.onPause();
        afterSavedInstanceState = true;
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        super.onDestroy();
    }

    public boolean isAfterSavedInstanceState() {
        return afterSavedInstanceState;
    }


}
