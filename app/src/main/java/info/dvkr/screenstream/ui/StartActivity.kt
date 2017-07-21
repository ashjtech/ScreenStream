package info.dvkr.screenstream.ui


import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.crashlytics.android.Crashlytics
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.mikepenz.materialdrawer.Drawer
import com.mikepenz.materialdrawer.DrawerBuilder
import com.mikepenz.materialdrawer.model.DividerDrawerItem
import com.mikepenz.materialdrawer.model.PrimaryDrawerItem
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.presenter.StartActivityPresenter
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.service.ForegroundServiceView
import kotlinx.android.synthetic.main.activity_start.*
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.subjects.PublishSubject
import java.net.BindException
import java.text.NumberFormat
import javax.inject.Inject

class StartActivity : BaseActivity(), StartActivityView {

    private val TAG = "StartActivity"

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 10

        private const val EXTRA_DATA = "EXTRA_DATA"
        const val ACTION_START_STREAM = "ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "ACTION_STOP_STREAM"
        const val ACTION_EXIT = "ACTION_EXIT"
        const val ACTION_ERROR = "ACTION_ERROR"

        fun getStartIntent(context: Context): Intent {
            return Intent(context, StartActivity::class.java)
        }

        fun getStartIntent(context: Context, action: String): Intent {
            return Intent(context, StartActivity::class.java)
                    .putExtra(EXTRA_DATA, action)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    @Inject internal lateinit var presenter: StartActivityPresenter
    @Inject internal lateinit var settings: Settings

    private val fromEvents = PublishSubject.create<StartActivityView.FromEvent>()

    private var oldClientsCount: Int = -1
    private lateinit var drawer: Drawer
    private lateinit var lineGraphSeries: LineGraphSeries<DataPoint>
    private var dialog: Dialog? = null

    override fun fromEvent(): Observable<StartActivityView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: StartActivityView.ToEvent) {
        Observable.just(toEvent).observeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: ${event.javaClass.simpleName}")

            when (event) {
                is StartActivityView.ToEvent.TryToStart -> {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
                }

                is StartActivityView.ToEvent.StreamStartStop -> {
                    setStreamRunning(event.running)
                    if (event.running && settings.minimizeOnStream)
                        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                is StartActivityView.ToEvent.ResizeFactor -> showResizeFactor(event.value)
                is StartActivityView.ToEvent.EnablePin -> showEnablePin(event.value)
                is StartActivityView.ToEvent.SetPin -> textViewPinValue.text = event.value
                is StartActivityView.ToEvent.StreamRunning -> setStreamRunning(event.running)

                is StartActivityView.ToEvent.Error -> {
                    toggleButtonStartStop.isEnabled = true
                    event.error?.let {
                        when (it) {
                            is UnsupportedOperationException -> showErrorDialog(getString(R.string.start_activity_error_wrong_image_format))
                            is BindException -> {
                                toggleButtonStartStop.isEnabled = false
                                showErrorDialog(getString(R.string.start_activity_error_port_in_use))
                            }
                            else -> showErrorDialog(getString(R.string.start_activity_error_unknown) + "\n${it.message}")
                        }
                    }
                }

                is StartActivityView.ToEvent.CurrentClients -> {
                    val clientsCount = event.clientsList.filter { !it.disconnected }.count()
                    if (clientsCount != oldClientsCount) {
                        textViewConnectedClients.text = getString(R.string.start_activity_connected_clients).format(clientsCount)
                        oldClientsCount = clientsCount
                    }
                }

                is StartActivityView.ToEvent.CurrentInterfaces -> {
                    showServerAddresses(event.interfaceList, settings.severPort)
                }

                is StartActivityView.ToEvent.TrafficHistory -> {
                    textViewCurrentTraffic.text = getString(R.string.start_activity_current_traffic).format(toMbit(event.trafficHistory.last().bytes))

                    val arrayOfDataPoints = event.trafficHistory.map { DataPoint(it.time.toDouble(), toMbit(it.bytes)) }.toTypedArray()
                    lineChartTraffic.removeAllSeries()
                    lineGraphSeries = LineGraphSeries<DataPoint>(arrayOfDataPoints)
                    lineGraphSeries.color = ContextCompat.getColor(this, R.color.colorAccent)
                    lineGraphSeries.thickness = 6
                    lineGraphSeries.isDrawBackground = true
//                    lineGraphSeries.backgroundColor = ContextCompat.getColor(this, R.color.colorDivider)
                    lineChartTraffic.addSeries(lineGraphSeries)
                    lineChartTraffic.viewport.isXAxisBoundsManual = true
                    lineChartTraffic.viewport.setMinX(arrayOfDataPoints[0].x)
                    lineChartTraffic.viewport.setMaxX(arrayOfDataPoints[arrayOfDataPoints.size - 1].x)
                    lineChartTraffic.gridLabelRenderer.isHorizontalLabelsVisible = false
                    lineChartTraffic.gridLabelRenderer.gridStyle = GridLabelRenderer.GridStyle.HORIZONTAL
                    val nf = NumberFormat.getInstance()
                    nf.minimumFractionDigits = 2
                    nf.maximumFractionDigits = 2
                    nf.minimumIntegerDigits = 1
                    lineChartTraffic.gridLabelRenderer.labelFormatter = DefaultLabelFormatter(nf, nf)
                    lineChartTraffic.gridLabelRenderer.horizontalLabelsColor = ContextCompat.getColor(this, R.color.colorPrimaryText)
                    lineChartTraffic.viewport.isYAxisBoundsManual = true
                    lineChartTraffic.viewport.setMinY(0.0)
                    lineChartTraffic.viewport.setMaxY(lineGraphSeries.highestValueY * 1.2)
                }

                is StartActivityView.ToEvent.TrafficPoint -> {
                    val mbit = toMbit(event.trafficPoint.bytes)
                    textViewCurrentTraffic.text = getString(R.string.start_activity_current_traffic).format(mbit)
                    lineGraphSeries.appendData(DataPoint(event.trafficPoint.time.toDouble(), mbit), true, 60)

                    lineChartTraffic.viewport.setMaxY(lineGraphSeries.highestValueY * 1.2)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")

        setContentView(R.layout.activity_start)
        setSupportActionBar(toolbarStart)
        supportActionBar?.setTitle(R.string.start_activity_name)

        startService(ForegroundService.getIntent(applicationContext, ForegroundService.ACTION_INIT))

        presenter.attach(this)

        toggleButtonStartStop.setOnClickListener { _ ->
            if (toggleButtonStartStop.isChecked) {
                toggleButtonStartStop.isChecked = false
                fromEvents.onNext(StartActivityView.FromEvent.TryStartStream())
            } else {
                toggleButtonStartStop.isChecked = true
                fromEvents.onNext(StartActivityView.FromEvent.StopStream())
            }
        }

        drawer = DrawerBuilder()
                .withActivity(this)
                .withToolbar(toolbarStart)
                .withHeader(R.layout.activity_start_drawer_header)
                .withHasStableIds(true)
                .addDrawerItems(
                        PrimaryDrawerItem().withIdentifier(1).withName("Main").withSelectable(false).withIcon(R.drawable.ic_drawer_main_24dp),
                        PrimaryDrawerItem().withIdentifier(2).withName("Connected clients").withSelectable(false).withIcon(R.drawable.ic_drawer_connected_24dp).withEnabled(false),
                        PrimaryDrawerItem().withIdentifier(3).withName("Settings").withSelectable(false).withIcon(R.drawable.ic_drawer_settings_24dp),
                        //                        DividerDrawerItem(),
//                        PrimaryDrawerItem().withIdentifier(4).withName("Instructions").withSelectable(false).withIcon(R.drawable.ic_drawer_instructions_24dp),
//                        PrimaryDrawerItem().withIdentifier(5).withName("Local test").withSelectable(false).withIcon(R.drawable.ic_drawer_test_24dp),
                        DividerDrawerItem(),
                        PrimaryDrawerItem().withIdentifier(6).withName("Rate app").withSelectable(false).withIcon(R.drawable.ic_drawer_rateapp_24dp),
                        PrimaryDrawerItem().withIdentifier(7).withName("Feedback").withSelectable(false).withIcon(R.drawable.ic_drawer_feedback_24dp),
                        PrimaryDrawerItem().withIdentifier(8).withName("Sources").withSelectable(false).withIcon(R.drawable.ic_drawer_sources_24dp)
                )
                .addStickyDrawerItems(
                        PrimaryDrawerItem().withIdentifier(9).withName("Exit").withIcon(R.drawable.ic_drawer_exit_24pd)
                )
                .withOnDrawerItemClickListener { _, _, drawerItem ->
                    if (drawerItem.identifier == 1L) if (drawer.isDrawerOpen) drawer.closeDrawer()

                    if (drawerItem.identifier == 2L) {
                    } // TODO

                    if (drawerItem.identifier == 3L) startActivity(SettingsActivity.getStartIntent(this))

                    if (drawerItem.identifier == 6L) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                        } catch (ex: ActivityNotFoundException) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                        }
                    }

                    if (drawerItem.identifier == 7L) {
                        val emailIntent = Intent(Intent.ACTION_SENDTO)
                                .setData(Uri.Builder().scheme("mailto").build())
                                .putExtra(Intent.EXTRA_EMAIL, arrayOf(StartActivityView.FEEDBACK_EMAIL_ADDRESS))
                                .putExtra(Intent.EXTRA_SUBJECT, StartActivityView.FEEDBACK_EMAIL_SUBJECT)
                        startActivity(Intent.createChooser(emailIntent, StartActivityView.FEEDBACK_EMAIL_NAME))
                    }

                    if (drawerItem.identifier == 8L) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dkrivoruchko/ScreenStream")))
                    }

                    if (drawerItem.identifier == 9L) fromEvents.onNext(StartActivityView.FromEvent.AppExit())
                    true
                }
                .build()

        drawer.deselect()

        showResizeFactor(settings.resizeFactor)
        showEnablePin(settings.enablePin)
        textViewPinValue.text = settings.currentPin

        onNewIntent(intent)

        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val action = intent.getStringExtra(EXTRA_DATA)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onNewIntent: action = $action")
        if (null == action) return

        when (action) {
            ACTION_START_STREAM -> {
                toggleButtonStartStop.isChecked = false
                fromEvents.onNext(StartActivityView.FromEvent.TryStartStream())
            }

            ACTION_STOP_STREAM -> {
                toggleButtonStartStop.isChecked = true
                fromEvents.onNext(StartActivityView.FromEvent.StopStream())
            }

            ACTION_EXIT -> fromEvents.onNext(StartActivityView.FromEvent.AppExit())
        }
    }

    override fun onStart() {
        super.onStart()
        fromEvents.onNext(StartActivityView.FromEvent.TrafficHistoryRequest())
    }

    override fun onResume() {
        super.onResume()
        fromEvents.onNext(StartActivityView.FromEvent.GetError())
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen) drawer.closeDrawer() else drawer.openDrawer()
    }

    override fun onStop() {
        if (drawer.isDrawerOpen) drawer.closeDrawer()
        super.onStop()
    }

    override fun inject(injector: NonConfigurationComponent) = injector.inject(this)

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Start")
        dialog?.let { if (it.isShowing) it.dismiss() }
        presenter.detach()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: End")
        super.onDestroy()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onActivityResult: $requestCode")
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (Activity.RESULT_OK != resultCode) {
                    showErrorDialog(getString(R.string.start_activity_error_cast_permission_deny))
                    if (BuildConfig.DEBUG_MODE) Log.w(TAG, "onActivityResult: Screen Cast permission denied")
                    return
                }

                if (null == data) {
                    if (BuildConfig.DEBUG_MODE) Log.e(TAG, "onActivityResult ERROR: data = null")
                    Crashlytics.logException(IllegalStateException("onActivityResult ERROR: data = null"))
                    showErrorDialog(getString(R.string.start_activity_error_unknown) + "onActivityResult: data = null")
                    return
                }
                startService(ForegroundService.getStartStreamIntent(applicationContext, data))
            }
        }
    }

    // Private methods
    private fun setStreamRunning(running: Boolean) {
        toggleButtonStartStop.isChecked = running
        if (settings.enablePin && settings.hidePinOnStart) {
            if (running) textViewPinValue.setText(R.string.start_activity_pin_asterisks)
            else textViewPinValue.text = settings.currentPin
        }
    }

    private fun showServerAddresses(interfaceList: List<ForegroundServiceView.Interface>, serverPort: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showServerAddresses")

        linearLayoutServerAddressList.removeAllViews()
        val layoutInflater = LayoutInflater.from(this)
        for ((name, address) in interfaceList) {
            val addressView = layoutInflater.inflate(R.layout.server_address, null)
            val interfaceView = addressView.findViewById(R.id.textViewInterfaceName) as TextView
            interfaceView.text = "$name:"
            val interfaceAddress = addressView.findViewById(R.id.textViewInterfaceAddress) as TextView
            interfaceAddress.text = "http://$address:$serverPort"
            linearLayoutServerAddressList.addView(addressView)
        }
    }

    private fun showResizeFactor(resizeFactor: Int) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showResizeFactor")

        val defaultDisplay = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val screenSize = Point()
        defaultDisplay.getSize(screenSize)
        val scale = resizeFactor / 100f
        textViewResizeFactor.text = getString(R.string.start_activity_resize_factor) +
                " $resizeFactor%: ${(screenSize.x * scale).toInt()}x${(screenSize.y * scale).toInt()}"
    }

    private fun showEnablePin(enablePin: Boolean) {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] showEnablePin")
        if (enablePin) {
            textViewPinValue.text = settings.currentPin
            textViewPinDisabled.visibility = View.GONE
            textViewPinText.visibility = View.VISIBLE
            textViewPinValue.visibility = View.VISIBLE
        } else {
            textViewPinDisabled.visibility = View.VISIBLE
            textViewPinText.visibility = View.GONE
            textViewPinValue.visibility = View.GONE
        }
    }

    private fun showErrorDialog(errorMessage: String) {
        dialog = AlertDialog.Builder(this)
                .setIcon(R.drawable.ic_message_error_24dp)
                .setTitle(R.string.start_activity_error_title)
                .setMessage(errorMessage)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        dialog?.show()
    }

    private fun toMbit(byte: Long) = (byte * 8).toDouble() / 1024 / 1024
}