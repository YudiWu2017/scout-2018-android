package com.example.evan.scout;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


import org.json.JSONArray;
import org.json.JSONException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class MainActivity extends AppCompatActivity {
    //uuid for bluetooth connection
    private static final String uuid = "f8212682-9a34-11e5-8994-feff819cdc9f";

    //paired device to connect to as super:
    private String superName;
    //private static final String redSuperName = "red super";
    //private static final String blueSuperName = "blue super";
    private static final String redSuperName = "Long Family Fire";
    private static final String blueSuperName = "G Pad 7.0 LTE";

    //current list of sent files
    private FileListAdapter fileListAdapter;

    //current match the scout is on
    private int matchNumber;

    //whether the automatic match progression is overridden or not
    private boolean overridden = false;

    //schedule of matches
    private ScheduleHandler schedule;

    //shared preferences to receive previous matchNumber, scoutNumber
    private SharedPreferences preferences;
    private static final String PREFERENCES_FILE = "com.example.evan.scout";

    //the id of the scout.  1-3 is red, 4-6 is blue
    private int scoutNumber;

    //we highlight the edittext that has the team number that this scout needs to scout, but if they change their id we need to reset it
    //this is the original background that was with the edittext
    private Drawable originalEditTextDrawable;

    //initials of scout scouting
    private String scoutName;

    //save a reference to this activity for subclasses
    private final MainActivity context = this;

    //lock for continueResend
    private static final Object continueResendLock = new Object();

    //when resending files, indicates whether the user pressed the 'cancel resend' button or not
    private boolean continueResend = true;

    //an onclicklistener for the 'resend all unsent' button, declared globally to be reused
    private View.OnClickListener originalResendAllUnsentOnClick;

    //onclick for 'resend all' button
    private View.OnClickListener originalResendAllOnClick;





    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //lock screen horizontal
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        //see comment on this variable above
        originalEditTextDrawable = findViewById(R.id.teamNumber1Edit).getBackground();



        //get any values received from other activities
        preferences = getSharedPreferences(PREFERENCES_FILE, 0);
        overridden = getIntent().getBooleanExtra("overridden", false);
        matchNumber = getIntent().getIntExtra("matchNumber", -1);
        //if matchNumber was not passed from a previous activity, load it from hard disk
        if (matchNumber == -1) {
            matchNumber = preferences.getInt("matchNumber", 1);
            //otherwise, save it to hard disk
        } else {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("matchNumber", matchNumber);
            editor.commit();
        }
        //scout initials
        scoutName = getIntent().getStringExtra("scoutName");

        schedule = new ScheduleHandler(this);
        schedule.getScheduleFromDisk();
        //if we don't have the schedule, they must enter the team numbers and it must be overridden.  If not, give them the choice
        if (schedule.getSchedule() == null) {
            override();
        } else if (overridden) {
            override();
        } else {
            automate();
        }

        scoutNumber = preferences.getInt("scoutNumber", -1);
        //if we don't have scout id, get it
        if (scoutNumber == -1) {
            setScoutNumber();
            //if we have it, change edittexts accordingly
        } else {
            highlightTeamNumberTexts();
        }




        //implement ui stuff
        //set the match number edittext's onclick to open a dialog.  We do this so the screen does not shrink and the user can see what he/she types
        final EditText matchNumberTextView = (EditText) findViewById(R.id.matchNumberText);
        matchNumberTextView.setText("Q" + Integer.toString(matchNumber));
        matchNumberTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //display dialog if overridden
                if (overridden) {
                    final EditText editText = new EditText(context);
                    editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                    editText.setHint("Match Number");
                    new AlertDialog.Builder(context)
                            .setTitle("Set Match")
                            .setView(editText)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    //when they click done, we get the matchnumber from what they put
                                    try {
                                        matchNumber = Integer.parseInt(editText.getText().toString());
                                    } catch (NumberFormatException nfe) {
                                        matchNumber = 1;
                                    }
                                    SharedPreferences.Editor editor = preferences.edit();
                                    editor.putInt("matchNumber", matchNumber);
                                    editor.commit();
                                    matchNumberTextView.setText("Q" + Integer.toString(matchNumber));
                                    updateTeamNumbers();
                                }
                            })
                            .show();
                }
            }
        });

        //text watcher for listview search bar
        final EditText searchBar = (EditText) findViewById(R.id.searchBar);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String text = searchBar.getText().toString();
                fileListAdapter.updateListView();
                if (!text.equals("")) {
                    //get list of files starting with text
                    //pass them off to filter fileListAdapter
                    fileListAdapter.filterListView(text);
                }
            }
        });

        ListView fileList = (ListView) findViewById(R.id.infoList);
        fileListAdapter = new FileListAdapter(this, fileList, uuid, superName);

        //initialize 'resend all unsent' button
        final Button resendAllUnsentButton = (Button) findViewById(R.id.resendAllUnsent);
        originalResendAllUnsentOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //first disable the resend all button (the other one)
                Button resendAll = (Button) findViewById(R.id.resendAll);
                resendAll.setClickable(false);
                //then add all the unsent file names to a list
                List<String> unsentFileNames = new ArrayList<>();
                for (int i = 0; i < fileListAdapter.getCount(); i++) {
                    String name = fileListAdapter.getItem(i);
                    if (name.contains("UNSENT_")) {
                        unsentFileNames.add(name);
                    }
                }
                //set the button to cancel the resend process
                resendAllUnsentButton.setText("cancel resend");
                resendAllUnsentButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        synchronized (continueResendLock) {
                            continueResend = false;
                        }
                        cancelUnsentResend();
                    }
                });
                //and then resend all the files
                resendAllFiles(new Runnable() {
                    @Override
                    public void run() {
                        cancelUnsentResend();
                    }
                }, unsentFileNames);
            }
        };
        resendAllUnsentButton.setOnClickListener(originalResendAllUnsentOnClick);

        //intialize 'resend all' button
        final Button resendAllButton = (Button) findViewById(R.id.resendAll);
        originalResendAllOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //first disable other button
                Button resendAllUnsent = (Button) findViewById(R.id.resendAllUnsent);
                resendAllUnsent.setClickable(false);
                //when clicked, make a list of files
                List<String> tmpFileNames = new ArrayList<>();
                for (int i = 0; i < fileListAdapter.getCount(); i++) {
                    String name = fileListAdapter.getItem(i);
                    tmpFileNames.add(i, name);
                }
                //set the button to cancel the resend process
                resendAllButton.setText("cancel resend");
                resendAllButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        synchronized (continueResendLock) {
                            continueResend = false;
                        }
                        cancelResend();
                    }
                });
                //and then resend all the files
                resendAllFiles(new Runnable() {
                    @Override
                    public void run() {
                        cancelResend();
                    }
                }, tmpFileNames);
            }
        };
        resendAllButton.setOnClickListener(originalResendAllOnClick);





        //teleop activity will send data here so errors show up on this screen
        String matchData = getIntent().getStringExtra("matchData");
        //if we have data from teleop activity
        if (matchData != null) {
            //if savedInstanceState is not null, it means that the onCreate has already been called for this activity.  We don't want to resend data
            if (savedInstanceState != null) {
                return;
            }
            Log.i("JSON before send", matchData);
            new ConnectThread(this, superName, uuid,
                    getIntent().getStringExtra("matchName") + "_" + new SimpleDateFormat("dd-H:mm", Locale.US).format(new Date()) + ".txt",
                    matchData + "\n").start();
        }
    }




    //highlight the edittext with the team number of the team that this scout will be scouting
    private void highlightTeamNumberTexts() {
        TextView scoutTeamText1 = (TextView) this.findViewById(R.id.teamNumber1Edit);
        TextView scoutTeamText2 = (TextView) this.findViewById(R.id.teamNumber2Edit);
        TextView scoutTeamText3 = (TextView) this.findViewById(R.id.teamNumber3Edit);
        if (scoutNumber%3 == 1) {
            scoutTeamText1.setBackgroundColor(Color.parseColor("#64FF64"));
            scoutTeamText2.setBackground(originalEditTextDrawable);
            scoutTeamText3.setBackground(originalEditTextDrawable);
        } else if (scoutNumber%3 == 2) {
            scoutTeamText2.setBackgroundColor(Color.parseColor("#64FF64"));
            scoutTeamText1.setBackground(originalEditTextDrawable);
            scoutTeamText3.setBackground(originalEditTextDrawable);
        } else if (scoutNumber%3 == 0) {
            scoutTeamText3.setBackgroundColor(Color.parseColor("#64FF64"));
            scoutTeamText1.setBackground(originalEditTextDrawable);
            scoutTeamText2.setBackground(originalEditTextDrawable);
        }



        //change ui depending on color
        if (scoutNumber < 4) {
            //update paired device name
            superName = redSuperName;

            //change actionbar color
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                //red
                actionBar.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#C40000")));
            }
        } else {
            //update paired device name
            superName = blueSuperName;

            //change actionbar color
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                //blue
                actionBar.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#4169e1")));
            }
        }
        updateTeamNumbers();
    }



    //fill in the edittexts with the team numbers found in the schedule
    public void updateTeamNumbers() {
        if (schedule.getSchedule() != null) {
            EditText teamNumber1Edit = (EditText) findViewById(R.id.teamNumber1Edit);
            EditText teamNumber2Edit = (EditText) findViewById(R.id.teamNumber2Edit);
            EditText teamNumber3Edit = (EditText) findViewById(R.id.teamNumber3Edit);
            Log.i("Schedule before display", schedule.getSchedule().toString());
            try {
                if (scoutNumber < 4) {
                    JSONArray red = schedule.getSchedule().getJSONObject("redTeamNumbers").getJSONArray(Integer.toString(matchNumber));
                    teamNumber1Edit.setText(red.getString(0));
                    teamNumber2Edit.setText(red.getString(1));
                    teamNumber3Edit.setText(red.getString(2));
                } else {
                    JSONArray blue = schedule.getSchedule().getJSONObject("blueTeamNumbers").getJSONArray(Integer.toString(matchNumber));
                    teamNumber1Edit.setText(blue.getString(0));
                    teamNumber2Edit.setText(blue.getString(1));
                    teamNumber3Edit.setText(blue.getString(2));
                }
            } catch (JSONException jsone) {
                Log.e("JSON error", "Failed to read JSON");
                teamNumber1Edit.setText("");
                teamNumber2Edit.setText("");
                teamNumber3Edit.setText("");
            }
        }
    }



    //recursive function to resend all the files in the list names, at a 5 second interval, while there are still files left and the user has not canceled the process
    //names is a list of filenames in the directory /sdcard/Android/ that need to be resent
    //cancel is a runnable that will be called to cancel the resend process
    private void resendAllFiles(final Runnable cancel, List<String> names) {
        boolean continueResend;
        synchronized (continueResendLock) {
            continueResend = this.continueResend;
        }
        //if the cancel button has not been clicked
        if (continueResend) {
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            String name;
            //get the first unsent file in the list, and remove it
            try {
                name = names.remove(0);
            } catch (IndexOutOfBoundsException ioobe) {
                cancel.run();
                return;
            }
            //send it to super
            String text = fileListAdapter.readFile(name);
            if (text != null) {
                new ConnectThread(context, superName, uuid, name, text).start();
            }
            if (names.size() != 0) {
                //finally if there is another file in the list, wait 5 seconds before sending it again
                final List<String> tmp = names;
                timer.schedule(new Runnable() {
                    @Override
                    public void run() {
                        resendAllFiles(cancel, tmp);
                    }
                }, 5, TimeUnit.SECONDS);
                return;
            }
            //if there is not another file in the list, stop the resend process
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancel.run();
                }
            });
        } else {
            //if the user did click the 'cancel resend' button, reset the flag before quitting
            synchronized (continueResendLock) {
                this.continueResend = true;
            }
        }
    }



    //cancel the 'resend all unsent' button
    private void cancelUnsentResend() {
        Button resendAllButton = (Button) findViewById(R.id.resendAllUnsent);
        resendAllButton.setText("resend all unsent");
        resendAllButton.setOnClickListener(originalResendAllUnsentOnClick);
        Button resendAll = (Button) findViewById(R.id.resendAll);
        resendAll.setClickable(true);
    }
    //cancel the 'resend all' button
    private void cancelResend() {
        Button resendAllButton = (Button) findViewById(R.id.resendAll);
        resendAllButton.setText("resend all");
        resendAllButton.setOnClickListener(originalResendAllOnClick);
        Button resendAll = (Button) findViewById(R.id.resendAllUnsent);
        resendAll.setClickable(true);
    }



    //update actionbar at top of screen, either giving them the option to override or automate
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!overridden) {
            //this is a menu with override as a button
            getMenuInflater().inflate(R.menu.main_menu, menu);
        } else {
            //this is a menu with automate as a button
            getMenuInflater().inflate(R.menu.main_menu2, menu);
        }
        return true;
    }



    //onclicks for buttons on actionbar
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        //override button
        if (item.getItemId() == R.id.mainOverride) {
            override();



            //automate button
        } else if (item.getItemId() == R.id.mainAutomate) {
            automate();



            //set scout id button
        } else if (item.getItemId() == R.id.setScoutIDButton) {
            setScoutNumber();


        }else if (item.getItemId() == R.id.setScoutName) {
            setScoutName(null);


            //get schedule button
        } else if (item.getItemId() == R.id.scheduleButton) {
            schedule.getScheduleFromSuper(superName, uuid);
        }
        return true;
    }



    //override the schedule
    private void override () {
        overridden = true;
        invalidateOptionsMenu();
    }



    //automate the schedule
    private void automate() {
        if (schedule.getSchedule() != null) {
            overridden = false;
            invalidateOptionsMenu();
        } else {
            Toast.makeText(this, "Schedule not available. Please get schedule", Toast.LENGTH_LONG).show();
        }
    }



    //display dialog to set scout number
    private void setScoutNumber() {
        final EditText editText = new EditText(this);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (scoutNumber == -1) {
            editText.setHint("Scout ID");
        } else {
            editText.setHint(Integer.toString(scoutNumber));
        }
        new AlertDialog.Builder(this)
                .setTitle("Set Scout ID")
                .setView(editText)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            scoutNumber = Integer.parseInt(editText.getText().toString());
                            if ((scoutNumber < 0) || (scoutNumber > 6)) {
                                throw new NumberFormatException();
                            }
                        } catch (NumberFormatException nfe) {
                            setScoutNumber();
                        }
                        highlightTeamNumberTexts();
                        ConnectThread.initBluetooth(context, superName);
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putInt("scoutNumber", scoutNumber);
                        editor.commit();
                    }
                })
                .show();
    }



    //onclick for edittexts containing team numbers
    //again, we display dialogs to prevent screen shrinking
    public void editTeamNumber(final View view) {
        if (overridden) {
            final EditText editText = new EditText(this);
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.setHint("Team Number");
            new AlertDialog.Builder(this)
                    .setTitle("Set Team Number")
                    .setView(editText)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            TextView textView = (TextView) view;
                            int teamNum;
                            try {
                                teamNum = Integer.parseInt(editText.getText().toString());
                            } catch (NumberFormatException nfe) {
                                return;
                            }
                            textView.setText(Integer.toString(teamNum));
                        }
                    })
                    .show();
        }
    }



    //scout button on ui
    public void startScoutButton (View view) {
        startScout(null, matchNumber, -1);
    }



    public void startScout(String editJSON, int matchNumber, int teamNumber) {
        //collect the team number
        if (teamNumber == -1) {
            try {
                if (scoutNumber % 3 == 1) {
                    TextView scoutTeamText = (TextView) findViewById(R.id.teamNumber1Edit);
                    teamNumber = Integer.parseInt(scoutTeamText.getText().toString());
                } else if (scoutNumber % 3 == 2) {
                    TextView scoutTeamText = (TextView) findViewById(R.id.teamNumber2Edit);
                    teamNumber = Integer.parseInt(scoutTeamText.getText().toString());
                } else if (scoutNumber % 3 == 0) {
                    TextView scoutTeamText = (TextView) findViewById(R.id.teamNumber3Edit);
                    teamNumber = Integer.parseInt(scoutTeamText.getText().toString());
                } else {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException nfe) {
                Toast.makeText(this, "Please enter valid team numbers", Toast.LENGTH_LONG).show();
                return;
            }
        }
        fileListAdapter.stopFileObserver();
        final Intent nextActivity = new Intent(context, AutoActivity.class)
                .putExtra("matchNumber", matchNumber).putExtra("overridden", overridden)
                .putExtra("teamNumber", teamNumber).putExtra("scoutName", scoutName).putExtra("scoutNumber", scoutNumber).putExtra("autoJSON", editJSON);
        if (scoutName == null) {
            setScoutName(new Runnable() {
                @Override
                public void run() {
                    startActivity(nextActivity.putExtra("scoutName", scoutName));
                }
            });
        } else {
            startActivity(nextActivity);
        }
    }



    //in order to redisplay the dialog to ask for scout initials, we start a new method, and recursively call the method if the input is wrong
    //on Finish is what to happen on click
    private void setScoutName(final Runnable onFinish) {
        final EditText editText = new EditText(this);
        editText.setHint("Scout Initials");
        new AlertDialog.Builder(this)
                .setTitle("Set Scout Initials")
                .setView(editText)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        scoutName = editText.getText().toString();
                        if (scoutName.equals("")) {
                            setScoutName(onFinish);
                        } else {
                            if (onFinish != null) {
                                onFinish.run();
                            }
                        }
                    }
                })
                .show();
    }
}
