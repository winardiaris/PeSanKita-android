/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;
import com.soundcloud.android.crop.Crop;

import org.thoughtcrime.securesms.components.PushRecipientsPanel;
import org.thoughtcrime.securesms.components.PushRecipientsPanel.RecipientsPanelChangedListener;
import org.thoughtcrime.securesms.contacts.RecipientsEditor;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhotoFactory;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.GroupManager.GroupActionResult;
import org.thoughtcrime.securesms.mms.RoundedCorners;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.SelectedRecipientsAdapter;
import org.thoughtcrime.securesms.util.SelectedRecipientsAdapter.OnRecipientDeletedListener;
import org.thoughtcrime.securesms.util.SelectedRecipientsAdapter.RecipientWrapper;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.util.InvalidNumberException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.thoughtcrime.securesms.R.id.recipients;

/**
 * Activity to create and update groups
 *
 * @author Jake McGinty
 */
public class GroupCreateActivity extends PassphraseRequiredActionBarActivity
                                 implements OnRecipientDeletedListener,
                                            RecipientsPanelChangedListener
{

  private final static String TAG = GroupCreateActivity.class.getSimpleName();

  public static final String GROUP_ADDRESS_EXTRA = "group_recipient";
  public static final String GROUP_THREAD_EXTRA  = "group_thread";

  private final DynamicTheme    dynamicTheme    = new DynamicTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private static final int PICK_CONTACT = 1;
  public static final  int AVATAR_SIZE  = 210;

  private EditText     groupName;
  private ListView     lv;
  private ImageView    avatar;
  private TextView     creatingText;
  private MasterSecret masterSecret;
  private Bitmap       avatarBmp;
  private ImageButton  contactsButton;
  private RecipientsEditor recipientsEditor;
  private TextView countValue;
  public int updateGroup = 1;

  @NonNull private Optional<GroupData> groupToUpdate = Optional.absent();

  @Override
  protected void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  protected void onCreate(Bundle state, @NonNull MasterSecret masterSecret) {
    this.masterSecret = masterSecret;

    setContentView(R.layout.group_create_activity);
    //noinspection ConstantConditions
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    initializeResources();
    initializeExistingGroup();
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
    updateViewState();
  }

  private boolean isSignalGroup() {
    return TextSecurePreferences.isPushRegistered(this) && !getAdapter().hasNonPushMembers();
  }

  private void disableSignalGroupViews(int reasonResId) {
    View pushDisabled = findViewById(R.id.push_disabled);
    pushDisabled.setVisibility(View.VISIBLE);
    ((TextView) findViewById(R.id.push_disabled_reason)).setText(reasonResId);
    avatar.setEnabled(false);
    groupName.setEnabled(false);
  }

  private void enableSignalGroupViews() {
    findViewById(R.id.push_disabled).setVisibility(View.GONE);
    //avatar.setEnabled(true);
    //groupName.setEnabled(true);
  }

  @SuppressWarnings("ConstantConditions")
  private void updateViewState() {
    if (!TextSecurePreferences.isPushRegistered(this)) {
      disableSignalGroupViews(R.string.GroupCreateActivity_youre_not_registered_for_signal);
      getSupportActionBar().setTitle(R.string.GroupCreateActivity_actionbar_mms_title);
    } else if (getAdapter().hasNonPushMembers()) {
      disableSignalGroupViews(R.string.GroupCreateActivity_contacts_dont_support_push);
      getSupportActionBar().setTitle(R.string.GroupCreateActivity_actionbar_mms_title);
    } else {
      enableSignalGroupViews();
      getSupportActionBar().setTitle(groupToUpdate.isPresent()
                                     ? R.string.GroupCreateActivity_actionbar_edit_title
                                     : R.string.GroupCreateActivity_actionbar_title);
    }
  }

  private static boolean isActiveInDirectory(Recipient recipient) {
    return recipient.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED;
  }

  private void addSelectedContacts(@NonNull Recipient... recipients) {
    new AddMembersTask(this).execute(recipients);
  }

  private void addSelectedContacts(@NonNull Collection<Recipient> recipients) {
    addSelectedContacts(recipients.toArray(new Recipient[recipients.size()]));
  }

  private void initializeResources() {
    recipientsEditor = ViewUtil.findById(this, R.id.recipients_text);
    PushRecipientsPanel recipientsPanel  = ViewUtil.findById(this, recipients);
    lv           = ViewUtil.findById(this, R.id.selected_contacts_list);
    avatar       = ViewUtil.findById(this, R.id.avatar);
    groupName    = ViewUtil.findById(this, R.id.group_name);
    groupName.setEnabled(false);
    countValue   = ViewUtil.findById(this, R.id.Label_count_value);
    creatingText = ViewUtil.findById(this, R.id.creating_group_text);
    contactsButton = ViewUtil.findById(this, R.id.contacts_button);
    SelectedRecipientsAdapter adapter = new SelectedRecipientsAdapter(this);
    adapter.setOnRecipientDeletedListener(this);
    lv.setAdapter(adapter);
    recipientsEditor.setVisibility(View.GONE);
    recipientsEditor.setHint(R.string.recipients_panel__add_members);
    recipientsPanel.setPanelChangeListener(this);
    contactsButton.setVisibility(View.GONE);
    contactsButton.setOnClickListener(new AddRecipientButtonListener());
    avatar.setImageDrawable(ContactPhotoFactory.getDefaultGroupPhoto()
                                               .asDrawable(this, ContactColors.UNKNOWN_COLOR.toConversationColor(this)));
    avatar.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        Crop.pickImage(GroupCreateActivity.this);
      }
    });
    avatar.setEnabled(false);
  }

  private void initializeExistingGroup() {
    final Address groupAddress = getIntent().getParcelableExtra(GROUP_ADDRESS_EXTRA);

    if (groupAddress != null) {
      new FillExistingGroupInfoAsyncTask(this).execute(groupAddress.toGroupString());
    } else {
      groupName.setEnabled(true);
      avatar.setEnabled(true);
      recipientsEditor.setVisibility(View.VISIBLE);
      contactsButton.setVisibility(View.VISIBLE);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    inflater.inflate(R.menu.group_create, menu);
    if(updateGroup == 0){
      menu.findItem(R.id.menu_create_group).setVisible(false);
    }
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
      case android.R.id.home:
        finish();
        return true;
      case R.id.menu_create_group:
        if (groupToUpdate.isPresent()) handleGroupUpdate();
        else                           handleGroupCreate();
        return true;
    }

    return false;
  }

  @Override
  public void onRecipientDeleted(Recipient recipient) {
    getAdapter().remove(recipient);
    updateViewState();
  }

  @Override
  public void onRecipientsPanelUpdate(List<Recipient> recipients) {
    if (recipients != null && !recipients.isEmpty()) addSelectedContacts(recipients);
  }

  private void handleGroupCreate() {
    if (getGroupName() == null || getGroupName().length() == 0) {
      Toast.makeText(getApplicationContext(), R.string.GroupCreateActivity_group_name_empty, Toast.LENGTH_SHORT).show();
      return ;
    }

    if (getAdapter().getCount() < 1) {
      Log.i(TAG, getString(R.string.GroupCreateActivity_contacts_no_members));
      Toast.makeText(getApplicationContext(), R.string.GroupCreateActivity_contacts_no_members, Toast.LENGTH_SHORT).show();
      return;
    }
    if (isSignalGroup()) {
      new CreateSignalGroupTask(this, masterSecret, avatarBmp, getGroupName(), getAdapter().getRecipients()).execute();
    } else {
      new CreateMmsGroupTask(this, masterSecret, getAdapter().getRecipients()).execute();
    }
  }

  private void handleGroupUpdate() {
    if (getGroupName() == null || getGroupName().length() == 0) {
      Toast.makeText(getApplicationContext(), R.string.GroupCreateActivity_group_name_empty, Toast.LENGTH_SHORT).show();
      return ;
    }

    new UpdateSignalGroupTask(this, masterSecret, groupToUpdate.get().id, avatarBmp,
                              getGroupName(), getAdapter().getRecipients(),
                              getAdapter().getAdminNumbers().get()).execute();
  }

  private void handleOpenConversation(long threadId, Recipient recipient) {
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT);
    intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.getAddress());
    startActivity(intent);
    finish();
  }

  private void handleDisplayContextMenu(int position) {
    RecipientWrapper rw = (RecipientWrapper) getAdapter().getItem(position);
    new RecipientContextDialog(this, rw).display();
  }

  private void handleRemoveRecipient(Recipient recipient) {
    onRecipientDeleted(recipient);
  }

  private void handleMakeAdmin(String number) {
    getAdapter().addAdmin(number);
  }

  private void handleRevokeAdmin(String number) {
    getAdapter().removeAdmin(number);
  }

  private SelectedRecipientsAdapter getAdapter() {
    return (SelectedRecipientsAdapter)lv.getAdapter();
  }

  private @Nullable String getGroupName() {
    return groupName.getText() != null ? groupName.getText().toString() : null;
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, final Intent data) {
    super.onActivityResult(reqCode, resultCode, data);
    Uri outputFile = Uri.fromFile(new File(getCacheDir(), "cropped"));

    if (data == null || resultCode != Activity.RESULT_OK)
      return;

    switch (reqCode) {
      case PICK_CONTACT:
        List<String> selected = data.getStringArrayListExtra("contacts");

        for (String contact : selected) {
          Address   address   = Address.fromExternal(this, contact);
          Recipient recipient = Recipient.from(this, address, false);

          addSelectedContacts(recipient);
        }
        break;

      case Crop.REQUEST_PICK:
        new Crop(data.getData()).output(outputFile).asSquare().start(this);
        break;
      case Crop.REQUEST_CROP:
        Glide.with(this).load(Crop.getOutput(data)).asBitmap()
             .skipMemoryCache(true)
             .diskCacheStrategy(DiskCacheStrategy.NONE)
             .centerCrop().override(AVATAR_SIZE, AVATAR_SIZE)
             .into(new SimpleTarget<Bitmap>() {
               @Override
               public void onResourceReady(Bitmap resource, GlideAnimation<? super Bitmap> glideAnimation) {
                 setAvatar(Crop.getOutput(data), resource);
               }
             });
    }
  }

  private class RecipientContextDialog {

    private GroupCreateActivity        activity;
    private final RecipientWrapper     wrapper;

    public RecipientContextDialog(GroupCreateActivity activity, RecipientWrapper wrapper) {
      this.activity    = activity;
      this.wrapper     = wrapper;
    }

    public void display() {
      String localNumber   = TextSecurePreferences.getLocalNumber(activity);
      boolean isLocalOwner = activity.getAdapter().isOwnerNumber(localNumber);
      boolean isLocalAdmin = activity.getAdapter().isAdminNumber(localNumber);
      if(!Util.isOwnNumber(activity, wrapper.getRecipient().getAddress()) &&
              !wrapper.isOwner() && (isLocalOwner || isLocalAdmin)) {
        List<String> actions = new LinkedList<>();
        actions.add(activity.getString(R.string.GroupCreateActivity_menu_remove_member,
                wrapper.getRecipientNameOrNumber()));
        if(isLocalOwner) {
          if(wrapper.isAdmin()) {
            actions.add(activity.getString(R.string.GroupCreateActivity_menu_revoke_group_admin));
          } else {
            actions.add(activity.getString(R.string.GroupCreateActivity_menu_make_group_admin));
          }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setCancelable(true);
        builder.setItems(actions.toArray(new String[actions.size()]), new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            switch (which) {
              case 0:
                handleRemoveRecipient(wrapper.getRecipient());
                break;
              case 1:
                if(wrapper.isAdmin()) {
                  handleRevokeAdmin(wrapper.getRecipient().getAddress().serialize());
                } else {
                  handleMakeAdmin(wrapper.getRecipient().getAddress().serialize());
                }
                break;
            }
          }
        });
        builder.show();
      }
    }
  }

  private class AddRecipientButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      Intent intent = new Intent(GroupCreateActivity.this, PushContactSelectionActivity.class);
      if (groupToUpdate.isPresent()) intent.putExtra(ContactSelectionListFragment.DISPLAY_MODE,
                                                     ContactSelectionListFragment.DISPLAY_MODE_PUSH_ONLY);

      ArrayList<String> numbers = new ArrayList<String>();
      for (Recipient recipient : getAdapter().getRecipients()) {
        numbers.add(recipient.getAddress().serialize());
      }

      intent.putStringArrayListExtra(ContactSelectionListFragment.PRE_SELECT, numbers);

      startActivityForResult(intent, PICK_CONTACT);
    }
  }

  private static class CreateMmsGroupTask extends AsyncTask<Void,Void,GroupActionResult> {
    private final GroupCreateActivity activity;
    private final MasterSecret        masterSecret;
    private final Set<Recipient>      members;

    public CreateMmsGroupTask(GroupCreateActivity activity, MasterSecret masterSecret, Set<Recipient> members) {
      this.activity     = activity;
      this.masterSecret = masterSecret;
      this.members      = members;
    }

    @Override
    protected GroupActionResult doInBackground(Void... avoid) {
      List<Address> memberAddresses = new LinkedList<>();

      for (Recipient recipient : members) {
        memberAddresses.add(recipient.getAddress());
      }

      String    groupId        = DatabaseFactory.getGroupDatabase(activity).getOrCreateGroupForMembers(memberAddresses, true);
      Recipient groupRecipient = Recipient.from(activity, Address.fromSerialized(groupId), true);
      long      threadId       = DatabaseFactory.getThreadDatabase(activity).getThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.DEFAULT);

      return new GroupActionResult(groupRecipient, threadId);
    }

    @Override
    protected void onPostExecute(GroupActionResult result) {
      activity.handleOpenConversation(result.getThreadId(), result.getGroupRecipient());
    }

    @Override
    protected void onProgressUpdate(Void... values) {
      super.onProgressUpdate(values);
    }
  }

  private abstract static class SignalGroupTask extends AsyncTask<Void,Void,Optional<GroupActionResult>> {
    protected GroupCreateActivity activity;
    protected MasterSecret        masterSecret;
    protected Bitmap              avatar;
    protected Set<Recipient>      members;
    protected String              owner;
    protected Set<String>         admins;
    protected String              name;

    public SignalGroupTask(GroupCreateActivity activity,
                           MasterSecret        masterSecret,
                           Bitmap              avatar,
                           String              name,
                           Set<Recipient>      members,
                           String              owner,
                           Set<String>         admins)
    {
      this.activity     = activity;
      this.masterSecret = masterSecret;
      this.avatar       = avatar;
      this.name         = name;
      this.members      = members;
      this.owner        = owner;
      this.admins       = admins;
    }

    @Override
    protected void onPreExecute() {
      activity.findViewById(R.id.group_details_layout).setVisibility(View.GONE);
      activity.findViewById(R.id.creating_group_layout).setVisibility(View.VISIBLE);
      activity.findViewById(R.id.menu_create_group).setVisibility(View.GONE);
        final int titleResId = activity.groupToUpdate.isPresent()
                             ? R.string.GroupCreateActivity_updating_group
                             : R.string.GroupCreateActivity_creating_group;
        activity.creatingText.setText(activity.getString(titleResId, activity.getGroupName()));
      }

    @Override
    protected void onPostExecute(Optional<GroupActionResult> groupActionResultOptional) {
      if (activity.isFinishing()) return;
      activity.findViewById(R.id.group_details_layout).setVisibility(View.VISIBLE);
      activity.findViewById(R.id.creating_group_layout).setVisibility(View.GONE);
      activity.findViewById(R.id.menu_create_group).setVisibility(View.VISIBLE);
    }
  }

  private static class CreateSignalGroupTask extends SignalGroupTask {
    public CreateSignalGroupTask(GroupCreateActivity activity, MasterSecret masterSecret,
                                 Bitmap avatar, String name, Set<Recipient> members) {
      super(activity, masterSecret, avatar, name, members, null, null);
    }

    @Override
    protected Optional<GroupActionResult> doInBackground(Void... aVoid) {
      return Optional.of(GroupManager.createGroup(activity, masterSecret, members, avatar, name, false));
    }

    @Override
    protected void onPostExecute(Optional<GroupActionResult> result) {
      if (result.isPresent() && result.get().getThreadId() > -1) {
        if (!activity.isFinishing()) {
          activity.handleOpenConversation(result.get().getThreadId(), result.get().getGroupRecipient());
        }
      } else {
        super.onPostExecute(result);
        Toast.makeText(activity.getApplicationContext(),
                       R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
      }
    }
  }

  private static class UpdateSignalGroupTask extends SignalGroupTask {
    private String groupId;

    public UpdateSignalGroupTask(GroupCreateActivity activity,
                                 MasterSecret masterSecret, String groupId, Bitmap avatar, String name,
                                 Set<Recipient> members, Set<String> admins)
    {
      super(activity, masterSecret, avatar, name, members, null, admins);
      this.groupId = groupId;
    }

    @Override
    protected Optional<GroupActionResult> doInBackground(Void... aVoid) {
      try {
        return Optional.of(GroupManager.updateGroup(activity, masterSecret, groupId, members, admins, avatar, name));
      } catch (InvalidNumberException e) {
        return Optional.absent();
      }
    }

    @Override
    protected void onPostExecute(Optional<GroupActionResult> result) {
      if (result.isPresent() && result.get().getThreadId() > -1) {
        if (!activity.isFinishing()) {
          Intent intent = activity.getIntent();
          intent.putExtra(GROUP_THREAD_EXTRA, result.get().getThreadId());
          intent.putExtra(GROUP_ADDRESS_EXTRA, result.get().getGroupRecipient().getAddress());
          activity.setResult(RESULT_OK, intent);
          activity.finish();
        }
      } else {
        super.onPostExecute(result);
        Toast.makeText(activity.getApplicationContext(),
                       R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
      }
    }
  }

  private static class AddMembersTask extends AsyncTask<Recipient,Void,List<AddMembersTask.Result>> {
    static class Result {
      Optional<Recipient> recipient;
      boolean             isPush;
      String              reason;

      public Result(@Nullable Recipient recipient, boolean isPush, @Nullable String reason) {
        this.recipient = Optional.fromNullable(recipient);
        this.isPush    = isPush;
        this.reason    = reason;
      }
    }

    private GroupCreateActivity activity;
    private boolean             failIfNotPush;

    public AddMembersTask(@NonNull GroupCreateActivity activity) {
      this.activity      = activity;
      this.failIfNotPush = activity.groupToUpdate.isPresent();
    }

    @Override
    protected List<Result> doInBackground(Recipient... recipients) {
      final List<Result> results = new LinkedList<>();

      for (Recipient recipient : recipients) {
        boolean isPush = isActiveInDirectory(recipient);

        if (failIfNotPush && !isPush) {
          results.add(new Result(null, false, activity.getString(R.string.GroupCreateActivity_cannot_add_non_push_to_existing_group,
                                                                 recipient.toShortString())));
        } else if (TextUtils.equals(TextSecurePreferences.getLocalNumber(activity), recipient.getAddress().serialize())) {
          results.add(new Result(null, false, activity.getString(R.string.GroupCreateActivity_youre_already_in_the_group)));
        } else {
          results.add(new Result(recipient, isPush, null));
        }
      }
      return results;
    }

    @Override
    protected void onPostExecute(List<Result> results) {
      if (activity.isFinishing()) return;

      for (Result result : results) {
        if (result.recipient.isPresent()) {
          activity.getAdapter().add(result.recipient.get(), result.isPush);
        } else {
          Toast.makeText(activity, result.reason, Toast.LENGTH_SHORT).show();
        }
      }
      activity.updateViewState();
    }
  }

  private static class FillExistingGroupInfoAsyncTask extends ProgressDialogAsyncTask<String,Void,Optional<GroupData>> {
    private GroupCreateActivity activity;

    public FillExistingGroupInfoAsyncTask(GroupCreateActivity activity) {
      super(activity,
            R.string.GroupCreateActivity_loading_group_details,
            R.string.please_wait);
      this.activity = activity;
    }

    @Override
    protected Optional<GroupData> doInBackground(String... groupIds) {
      final GroupDatabase         db               = DatabaseFactory.getGroupDatabase(activity);
      final List<Recipient>       recipients       = db.getGroupMembers(groupIds[0], true);
      final Optional<GroupRecord> group            = db.getGroup(groupIds[0]);
      final Set<Recipient>  existingContacts       = new HashSet<>(recipients.size());
      final Set<Address>    existingAdmins         = db.getGroupAdmins(groupIds[0]);
      existingContacts.addAll(recipients);

      final Set<String> admins = new HashSet<>();
      for (Address admin : existingAdmins) {
        admins.add(admin.serialize());
      }

      if (group.isPresent()) {
        return Optional.of(new GroupData(groupIds[0],
                                         existingContacts,
                                         group.get().getOwner().serialize(),
                                         admins,
                                         BitmapUtil.fromByteArray(group.get().getAvatar()),
                                         group.get().getAvatar(),
                                         group.get().getTitle()));
      } else {
        return Optional.absent();
      }
    }

    @Override
    protected void onPostExecute(Optional<GroupData> group) {
      super.onPostExecute(group);
        LinkedList<Recipient> members = new LinkedList<>();
        List<Recipient> savedNumbers  = new ArrayList<>();
        List<Recipient> onlyNumbers   = new ArrayList<>();

        for (Recipient recipient : group.get().recipients) {
          if (Util.isOwnNumber(getContext(), recipient.getAddress())) {
            members.add(recipient);
          } else if (recipient.getName() == null) {
            onlyNumbers.add(recipient);
          } else {
            savedNumbers.add(recipient);
          }
        }

      Collections.sort(savedNumbers, new Comparator<Recipient>() {
        @Override
        public int compare(Recipient o1, Recipient o2) {
          return o1.getName().compareToIgnoreCase(o2.getName());
        }
      });
      Collections.sort(onlyNumbers, new Comparator<Recipient>() {
        @Override
        public int compare(Recipient o1, Recipient o2) {
          return o1.getAddress().serialize().compareToIgnoreCase(o2.getAddress().serialize());
        }
      });

      members.addAll(savedNumbers);
      members.addAll(onlyNumbers);

      if (group.isPresent() && !activity.isFinishing()) {
        activity.groupToUpdate = group;

        activity.groupName.setText(group.get().name);
        activity.countValue.setText(String.valueOf(group.get().recipients.size()));
        if (group.get().avatarBmp != null) {
          activity.setAvatar(group.get().avatarBytes, group.get().avatarBmp);
        }
        SelectedRecipientsAdapter adapter = new SelectedRecipientsAdapter(activity, members, group.get().owner, group.get().admins);
        adapter.setOnRecipientDeletedListener(activity);
        activity.lv.setAdapter(adapter);
        activity.lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
          @Override
          public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            activity.handleDisplayContextMenu(position);
          }
        });
        activity.updateViewState();
        String localNumber = TextSecurePreferences.getLocalNumber(activity);
        if (adapter.isOwnerNumber(localNumber) || adapter.isAdminNumber(localNumber)) {
          activity.groupName.setEnabled(true);
          activity.avatar.setEnabled(true);
          activity.recipientsEditor.setVisibility(View.VISIBLE);
          activity.contactsButton.setVisibility(View.VISIBLE);
        } else {
          activity.updateGroup = 0;
          activity.supportInvalidateOptionsMenu();
        }
      } else if (!group.isPresent()) {
        activity.groupName.setEnabled(true);
        activity.avatar.setEnabled(true);
        activity.recipientsEditor.setVisibility(View.VISIBLE);
        activity.contactsButton.setVisibility(View.VISIBLE);
      }
    }
  }

  private <T> void setAvatar(T model, Bitmap bitmap) {
    avatarBmp = bitmap;
    Glide.with(this)
         .load(model)
         .skipMemoryCache(true)
         .diskCacheStrategy(DiskCacheStrategy.NONE)
         .transform(new RoundedCorners(this, avatar.getWidth() / 2))
         .into(avatar);
  }

  private static class GroupData {
    String         id;
    Set<Recipient> recipients;
    String         owner;
    Set<String>    admins;
    Bitmap         avatarBmp;
    byte[]         avatarBytes;
    String         name;

    public GroupData(String id, Set<Recipient> recipients, String owner, Set<String> admins,
                     Bitmap avatarBmp, byte[] avatarBytes, String name) {
      this.id          = id;
      this.recipients  = recipients;
      this.owner       = owner;
      this.admins      = admins;
      this.avatarBmp   = avatarBmp;
      this.avatarBytes = avatarBytes;
      this.name        = name;
    }
  }
}
