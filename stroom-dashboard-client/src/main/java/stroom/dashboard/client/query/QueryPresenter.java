/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.dashboard.client.query;

import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;
import stroom.alert.client.event.AlertEvent;
import stroom.dashboard.client.main.AbstractComponentPresenter;
import stroom.dashboard.client.main.ComponentRegistry.ComponentType;
import stroom.dashboard.client.main.DashboardUUID;
import stroom.dashboard.client.main.IndexLoader;
import stroom.dashboard.client.main.SearchBus;
import stroom.dashboard.client.main.SearchModel;
import stroom.dashboard.client.main.UsesParams;
import stroom.dashboard.client.table.TimeZones;
import stroom.dashboard.shared.Automate;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.ComponentSettings;
import stroom.dashboard.shared.Dashboard;
import stroom.dashboard.shared.DataSourceFieldsMap;
import stroom.dashboard.shared.QueryComponentSettings;
import stroom.datasource.api.v1.DataSourceField;
import stroom.dispatch.client.ClientDispatchAsync;
import stroom.entity.client.event.DirtyEvent;
import stroom.entity.client.event.DirtyEvent.DirtyHandler;
import stroom.entity.client.event.HasDirtyHandlers;
import stroom.explorer.client.presenter.EntityChooser;
import stroom.node.client.ClientPropertyCache;
import stroom.node.shared.ClientProperties;
import stroom.pipeline.client.event.CreateProcessorEvent;
import stroom.pipeline.shared.PipelineEntity;
import stroom.process.shared.CreateProcessorAction;
import stroom.query.api.v1.DocRef;
import stroom.query.api.v1.ExpressionOperator;
import stroom.query.client.ExpressionTreePresenter;
import stroom.query.client.ExpressionUiHandlers;
import stroom.security.client.ClientSecurityContext;
import stroom.security.shared.DocumentPermissionNames;
import stroom.streamstore.shared.FindStreamCriteria;
import stroom.streamstore.shared.Limits;
import stroom.streamstore.shared.QueryData;
import stroom.streamtask.shared.StreamProcessor;
import stroom.util.shared.EqualsBuilder;
import stroom.util.shared.ModelStringUtil;
import stroom.widget.button.client.GlyphButtonView;
import stroom.widget.button.client.GlyphIcon;
import stroom.widget.button.client.GlyphIcons;
import stroom.widget.button.client.ImageButtonView;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.MenuListPresenter;
import stroom.widget.popup.client.event.HidePopupEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupUiHandlers;
import stroom.widget.popup.client.presenter.PopupView.PopupType;
import stroom.widget.tab.client.presenter.ImageIcon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class QueryPresenter extends AbstractComponentPresenter<QueryPresenter.QueryView>
        implements QueryUiHandlers, HasDirtyHandlers, UsesParams {

    public static final ComponentType TYPE = new ComponentType(0, "query", "Query");
    public static final int TEN_SECONDS = 10000;

    private static final long DEFAULT_TIME_LIMIT = 30L;
    private static final long DEFAULT_RECORD_LIMIT = 1000000L;

    private final ExpressionTreePresenter expressionPresenter;
    private final QueryHistoryPresenter historyPresenter;
    private final QueryFavouritesPresenter favouritesPresenter;
    private final Provider<EntityChooser> pipelineSelection;
    private final ProcessorLimitsPresenter processorLimitsPresenter;
    private final Resources resources;
    private final MenuListPresenter menuListPresenter;
    private final ClientDispatchAsync dispatcher;

    private final IndexLoader indexLoader;
    private final SearchModel searchModel;
    private final ImageButtonView addOperatorButton;
    private final GlyphButtonView addTermButton;
    private final GlyphButtonView disableItemButton;
    private final GlyphButtonView deleteItemButton;
    private final ImageButtonView historyButton;
    private final ImageButtonView favouriteButton;
    private final ImageButtonView warningsButton;

    private String params;
    private QueryComponentSettings queryComponentSettings;
    private String currentWarnings;
    private ImageButtonView processButton;
    private long defaultProcessorTimeLimit = DEFAULT_TIME_LIMIT;
    private long defaultProcessorRecordLimit = DEFAULT_RECORD_LIMIT;
    private boolean initialised;
    private Timer autoRefreshTimer;

    @Inject
    public QueryPresenter(final EventBus eventBus, final QueryView view, final SearchBus searchBus,
                          final Provider<QuerySettingsPresenter> settingsPresenterProvider,
                          final ExpressionTreePresenter expressionPresenter, final QueryHistoryPresenter historyPresenter,
                          final QueryFavouritesPresenter favouritesPresenter,
                          final Provider<EntityChooser> pipelineSelection,
                          final ProcessorLimitsPresenter processorLimitsPresenter, final Resources resources,
                          final MenuListPresenter menuListPresenter, final ClientDispatchAsync dispatcher,
                          final ClientSecurityContext securityContext, final ClientPropertyCache clientPropertyCache,
                          final TimeZones timeZones) {
        super(eventBus, view, settingsPresenterProvider);
        this.expressionPresenter = expressionPresenter;
        this.historyPresenter = historyPresenter;
        this.favouritesPresenter = favouritesPresenter;
        this.pipelineSelection = pipelineSelection;
        this.processorLimitsPresenter = processorLimitsPresenter;
        this.menuListPresenter = menuListPresenter;
        this.resources = resources;
        this.dispatcher = dispatcher;

        view.setExpressionView(expressionPresenter.getView());
        view.setUiHandlers(this);

        expressionPresenter.setUiHandlers(new ExpressionUiHandlers() {
            @Override
            public void fireDirty() {
                setDirty(true);
            }

            @Override
            public void search() {
                start();
            }
        });

        addTermButton = view.addButton(GlyphIcons.ADD);
        addTermButton.setTitle("Add Term");
        addOperatorButton = view.addButton("Add Operator", resources.addOperator(), resources.addOperator(), true);
        disableItemButton = view.addButton(GlyphIcons.DISABLE);
        deleteItemButton = view.addButton(GlyphIcons.DELETE);
        historyButton = view.addButton("History", resources.history(), null, true);
        favouriteButton = view.addButton("Favourites", resources.favourite(), null, true);

        if (securityContext.hasAppPermission(StreamProcessor.MANAGE_PROCESSORS_PERMISSION)) {
            processButton = view.addButton("Process", resources.pipeline(), resources.pipelineDisabled(), true);
        }

        warningsButton = view.addButton("Show Warnings", resources.warning(), null, true);
        warningsButton.setVisible(false);

        indexLoader = new IndexLoader(getEventBus(), dispatcher);
        searchModel = new SearchModel(searchBus, this, indexLoader, timeZones);

        clientPropertyCache.get()
                .onSuccess(result -> {
                    defaultProcessorTimeLimit = result.getLong(ClientProperties.PROCESS_TIME_LIMIT, DEFAULT_TIME_LIMIT);
                    defaultProcessorRecordLimit = result.getLong(ClientProperties.PROCESS_RECORD_LIMIT,
                            DEFAULT_RECORD_LIMIT);
                })
                .onFailure(caught -> AlertEvent.fireError(QueryPresenter.this, caught.getMessage(), null));
    }

    @Override
    protected void onBind() {
        super.onBind();

        registerHandler(expressionPresenter.addDataSelectionHandler(event -> setButtonsEnabled()));
        registerHandler(expressionPresenter.addContextMenuHandler(event -> {
            final List<Item> menuItems = addExpressionActionsToMenu();
            if (menuItems != null && menuItems.size() > 0) {
                final PopupPosition popupPosition = new PopupPosition(event.getX(), event.getY());
                showMenu(popupPosition, menuItems);
            }
        }));
        registerHandler(addOperatorButton.addClickHandler(event -> {
            if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                addOperator();
            }
        }));
        registerHandler(addTermButton.addClickHandler(event -> {
            if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                addTerm();
            }
        }));
        registerHandler(disableItemButton.addClickHandler(event -> {
            if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                disable();
            }
        }));
        registerHandler(deleteItemButton.addClickHandler(event -> {
            if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                delete();
            }
        }));
        registerHandler(historyButton.addClickHandler(event -> {
            if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                historyPresenter.show(QueryPresenter.this, getComponents().getDashboard().getId());
            }
        }));
        registerHandler(favouriteButton.addClickHandler( event-> {
                if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                    final ExpressionOperator root =
                    expressionPresenter.write();
                    favouritesPresenter.show(QueryPresenter.this, getComponents().getDashboard().getId(),
                            getSettings().getDataSource(), root);

            }
        }));
        if (processButton != null) {
            registerHandler(processButton.addClickHandler(event -> {
                if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                    choosePipeline();
                }
            }));
        }
        registerHandler(warningsButton.addClickHandler(event -> {
            if ((event.getNativeButton() & NativeEvent.BUTTON_LEFT) != 0) {
                showWarnings();
            }
        }));
        registerHandler(indexLoader.addChangeDataHandler(event -> loadedDataSource(indexLoader.getLoadedDataSourceRef(), indexLoader.getDataSourceFieldsMap())));
    }

    public void setErrors(final String errors) {
        currentWarnings = errors;
        warningsButton.setVisible(currentWarnings != null && currentWarnings.length() > 0);
    }

    private void setButtonsEnabled() {
        final stroom.query.client.Item selectedItem = getSelectedItem();

        if (selectedItem == null) {
            disableItemButton.setEnabled(false);
            disableItemButton.setTitle("");
        } else {
            disableItemButton.setEnabled(true);
            disableItemButton.setTitle(getEnableDisableText());
        }

        if (selectedItem == null) {
            deleteItemButton.setEnabled(false);
            deleteItemButton.setTitle("");
        } else {
            deleteItemButton.setEnabled(true);
            deleteItemButton.setTitle("Delete");
        }
    }

    private void loadDataSource(final DocRef dataSourceRef) {
        searchModel.getIndexLoader().loadDataSource(dataSourceRef);
    }

    private void loadedDataSource(final DocRef dataSourceRef, final DataSourceFieldsMap dataSourceFieldsMap) {
        // Create a list of index fields.
        final List<DataSourceField> indexedFields = new ArrayList<>();
        if (dataSourceFieldsMap != null) {
            for (final DataSourceField indexField : dataSourceFieldsMap.values()) {
                if (indexField.getQueryable()) {
                    indexedFields.add(indexField);
                }
            }
        }
        Collections.sort(indexedFields, Comparator.comparing(DataSourceField::getName));
        expressionPresenter.setFields(indexedFields);

        final EqualsBuilder builder = new EqualsBuilder();
        builder.append(queryComponentSettings.getDataSource(), dataSourceRef);

        if (!builder.isEquals()) {
            queryComponentSettings.setDataSource(dataSourceRef);
            setDirty(true);
        }

        // Only allow searching if we have a data source and have loaded fields from it successfully.
        getView().setEnabled(dataSourceRef != null && indexedFields.size() > 0);

        init();
    }

    private void addOperator() {
        expressionPresenter.addOperator();
    }

    private void addTerm() {
        final DocRef dataSourceRef = queryComponentSettings.getDataSource();

        if (dataSourceRef == null) {
            warnNoDataSource();
        } else {
            expressionPresenter.addTerm();
        }
    }

    private void warnNoDataSource() {
        AlertEvent.fireWarn(this, "No data source has been chosen to search", null);
    }

    private void disable() {
        expressionPresenter.disable();
        setButtonsEnabled();
    }

    private void delete() {
        expressionPresenter.delete();
    }

    private void choosePipeline() {
        expressionPresenter.clearSelection();
        // Write expression.
        final ExpressionOperator root = expressionPresenter.write();

        final QueryData queryData = new QueryData();
        queryData.setDataSource(queryComponentSettings.getDataSource());
        queryData.setExpression(root);

        final EntityChooser chooser = pipelineSelection.get();
        chooser.setCaption("Choose Pipeline To Process Results With");
        chooser.setIncludedTypes(PipelineEntity.ENTITY_TYPE);
        chooser.setRequiredPermissions(DocumentPermissionNames.USE);
        chooser.addDataSelectionHandler(event -> {
            final DocRef pipeline = chooser.getSelectedEntityReference();
            if (pipeline != null) {
                setProcessorLimits(queryData, pipeline);
            }
        });

        chooser.show();
    }

    private void setProcessorLimits(final QueryData queryData, final DocRef pipeline) {
        processorLimitsPresenter.setTimeLimitMins(defaultProcessorTimeLimit);
        processorLimitsPresenter.setRecordLimit(defaultProcessorRecordLimit);
        final PopupSize popupSize = new PopupSize(321, 102, false);
        ShowPopupEvent.fire(this, processorLimitsPresenter, PopupType.OK_CANCEL_DIALOG, popupSize,
                "Process Search Results", new PopupUiHandlers() {
                    @Override
                    public void onHideRequest(final boolean autoClose, final boolean ok) {
                        if (ok) {
                            final Limits limits = new Limits();
                            if (processorLimitsPresenter.getRecordLimit() != null) {
                                limits.setEventCount(processorLimitsPresenter.getRecordLimit());
                            }
                            if (processorLimitsPresenter.getTimeLimitMins() != null) {
                                limits.setDurationMs(processorLimitsPresenter.getTimeLimitMins() * 60 * 1000);
                            }
                            queryData.setLimits(limits);
                            openEditor(queryData, pipeline);
                        }
                        HidePopupEvent.fire(QueryPresenter.this, processorLimitsPresenter);
                    }

                    @Override
                    public void onHide(final boolean autoClose, final boolean ok) {
                    }
                });
    }

    private void openEditor(final QueryData queryData, final DocRef pipeline) {
        // Now create the processor filter using the find stream criteria.
        final FindStreamCriteria findStreamCriteria = new FindStreamCriteria();
        findStreamCriteria.setQueryData(queryData);
        dispatcher.exec(new CreateProcessorAction(pipeline, findStreamCriteria, true, 1)).onSuccess(streamProcessorFilter -> CreateProcessorEvent.fire(QueryPresenter.this, streamProcessorFilter));
    }

    private void showWarnings() {
        if (currentWarnings != null && currentWarnings.length() > 0) {
            AlertEvent.fireWarn(this, "The following warnings have been created while running this search:",
                    currentWarnings, null);
        }
    }

    @Override
    public void onParamsChanged(final String params) {
        this.params = params;
        if (initialised) {
            stop();
            start();
        }
    }

    @Override
    public void start() {
        run(true);
    }

    @Override
    public void stop() {
        if (autoRefreshTimer != null) {
            autoRefreshTimer.cancel();
            autoRefreshTimer = null;
        }
        searchModel.destroy();
    }

    private void run(final boolean incremental) {
        final DocRef dataSourceRef = queryComponentSettings.getDataSource();

        if (dataSourceRef == null) {
            warnNoDataSource();
        } else {
            currentWarnings = null;
            expressionPresenter.clearSelection();

            warningsButton.setVisible(false);

            // Write expression.
            final ExpressionOperator root = expressionPresenter.write();

            searchModel.search(root, params, incremental);
        }
    }

    @Override
    public void read(final ComponentConfig componentData) {
        super.read(componentData);
        queryComponentSettings = getSettings();

        // Create and register the search model.
        final Dashboard dashboard = getComponents().getDashboard();
        final DashboardUUID dashboardUUID = new DashboardUUID(dashboard.getId(), dashboard.getName(), getComponentData().getId());
        searchModel.setDashboardUUID(dashboardUUID);

        // Read data source.
        loadDataSource(queryComponentSettings.getDataSource());

        // Read expression.
        final ExpressionOperator root = queryComponentSettings.getExpression();
        setExpression(root);
    }

    @Override
    public void write(final ComponentConfig componentData) {
        super.write(componentData);

        // Write expression.
        queryComponentSettings.setExpression(expressionPresenter.write());
        componentData.setSettings(queryComponentSettings);
    }

    @Override
    public void onRemove() {
        super.onRemove();
        searchModel.destroy();
    }

    @Override
    public void link() {
    }

    private void init() {
        if (!initialised) {
            initialised = true;
            // An auto search can only commence if the UI has fully loaded and the data source has also loaded from the server.
            final Automate automate = getAutomate();
            if (automate.isOpen()) {
                run(true);
            }
        }
    }

    @Override
    public void changeSettings() {
        super.changeSettings();
        loadDataSource(queryComponentSettings.getDataSource());
    }

    @Override
    public HandlerRegistration addDirtyHandler(final DirtyHandler handler) {
        return addHandlerToSource(DirtyEvent.getType(), handler);
    }

    @Override
    public ComponentType getType() {
        return TYPE;
    }

    private QueryComponentSettings getSettings() {
        ComponentSettings settings = getComponentData().getSettings();
        if (settings == null || !(settings instanceof QueryComponentSettings)) {
            settings = createSettings();
            getComponentData().setSettings(settings);
        }

        return (QueryComponentSettings) settings;
    }

    private Automate getAutomate() {
        final QueryComponentSettings queryComponentSettings = getSettings();
        Automate automate = queryComponentSettings.getAutomate();
        if (automate == null) {
            automate = new Automate();
            queryComponentSettings.setAutomate(automate);
        }

        return automate;
    }

    private ComponentSettings createSettings() {
        return new QueryComponentSettings();
    }

    public SearchModel getSearchModel() {
        return searchModel;
    }

    public void setExpression(final ExpressionOperator root) {
        expressionPresenter.read(root);
    }

    public void setMode(final SearchModel.Mode mode) {
        getView().setMode(mode);

        // If this is the end of a query then schedule a refresh.
        if (SearchModel.Mode.INACTIVE.equals(mode)) {
            scheduleRefresh();
        }
    }

    private void scheduleRefresh() {
        // Schedule auto refresh after a query has finished.
        if (autoRefreshTimer != null) {
            autoRefreshTimer.cancel();
        }
        autoRefreshTimer = null;

        final Automate automate = getAutomate();
        if (automate.isRefresh()) {
            try {
                final String interval = automate.getRefreshInterval();
                int millis = ModelStringUtil.parseDurationString(interval).intValue();

                // Ensure that the refresh interval is not less than 10 seconds.
                millis = Math.max(millis, TEN_SECONDS);

                autoRefreshTimer = new Timer() {
                    @Override
                    public void run() {
                        // Make sure search is currently inactive before we attempt to execute a new query.
                        if (SearchModel.Mode.INACTIVE.equals(searchModel.getMode())) {
                            QueryPresenter.this.run(false);
                        }
                    }
                };
                autoRefreshTimer.schedule(millis);
            } catch (final Exception e) {
                // Ignore as we cannot display this error now.
            }
        }
    }

    private List<Item> addExpressionActionsToMenu() {
        final stroom.query.client.Item selectedItem = getSelectedItem();
        final boolean hasSelection = selectedItem != null;

        final List<Item> menuItems = new ArrayList<Item>();
        menuItems.add(new IconMenuItem(1, GlyphIcons.ADD, GlyphIcons.ADD, "Add Term", null, true, () -> addTerm()));
        menuItems.add(new IconMenuItem(2, ImageIcon.create(resources.addOperator()), ImageIcon.create(resources.addOperator()), "Add Operator", null,
                true, () -> addOperator()));
        menuItems.add(new IconMenuItem(3, GlyphIcons.DISABLE, GlyphIcons.DISABLE, getEnableDisableText(),
                null, hasSelection, () -> disable()));
        menuItems.add(new IconMenuItem(4, GlyphIcons.DELETE, GlyphIcons.DELETE, "Delete", null,
                hasSelection, () -> delete()));

        return menuItems;
    }

    private String getEnableDisableText() {
        final stroom.query.client.Item selectedItem = getSelectedItem();
        if (selectedItem != null && !selectedItem.enabled()) {
            return "Enable";
        }
        return "Disable";
    }

    private stroom.query.client.Item getSelectedItem() {
        if (expressionPresenter.getSelectionModel() != null) {
            return expressionPresenter.getSelectionModel().getSelectedObject();
        }
        return null;
    }

    private void showMenu(final PopupPosition popupPosition, final List<Item> menuItems) {
        menuListPresenter.setData(menuItems);

        final PopupUiHandlers popupUiHandlers = new PopupUiHandlers() {
            @Override
            public void onHideRequest(final boolean autoClose, final boolean ok) {
                HidePopupEvent.fire(QueryPresenter.this, menuListPresenter);
            }

            @Override
            public void onHide(final boolean autoClose, final boolean ok) {
            }
        };
        ShowPopupEvent.fire(this, menuListPresenter, PopupType.POPUP, popupPosition, popupUiHandlers);
    }

    public interface QueryView extends View, HasUiHandlers<QueryUiHandlers> {
        ImageButtonView addButton(String title, ImageResource enabledImage, ImageResource disabledImage,
                                  boolean enabled);

        GlyphButtonView addButton(GlyphIcon preset);

        void setExpressionView(View view);

        void setMode(SearchModel.Mode mode);

        void setEnabled(boolean enabled);
    }

    public interface Resources extends ClientBundle {
        ImageResource addOperator();

        ImageResource search();

        ImageResource history();

        ImageResource favourite();

        ImageResource pipeline();

        ImageResource pipelineDisabled();

        ImageResource warning();
    }
}
