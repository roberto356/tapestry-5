Tapestry.DateField = Class.create();

Tapestry.DateField.prototype = {

    // Initializes a DateField from a JSON specification.

    initialize : function(spec)
    {
        this.field = $(spec.field);
        this.trigger = $(spec.field + ":trigger");

        this.trigger.observe("click", this.triggerClicked.bind(this));

        this.popup = null;
    },

    triggerClicked : function()
    {
        if (this.field.disabled) return;

        if (this.popup == null)
        {
            this.createPopup();

        }
        else
        {
            if (this.popup.visible())
            {
                this.hidePopup();
                return;
            }
        }


        var value = $F(this.field);

        if (value == "")
        {
            this.datePicker.setDate(null);
        }
        else
        {

            // TODO: This is limited and americanized (not localized) to MM/DD/YYYY
            var re = /^\s*(\d+)\/(\d+)\/(\d{2,4})\s*$/;
            var matches = re.exec(value);


            // If the RE is bad, raise the date picker anyway, showing
            // the last valid date, or showing no date.

            if (matches != null)
            {

                var month = Number(matches[1]);
                var day = Number(matches[2])
                var year = Number(matches[3]);

            // For two digits, guestamate which century they want.

                if (year < 100)
                {
                    if (year >= 60) year += 1900
                    else year += 2000;
                }

                var date = new Date(value);

                date.setMonth(month - 1);
                date.setDate(day);
                date.setFullYear(year);

                this.datePicker.setDate(date);
            }
        }

        this.positionPopup();

        this.revealPopup();
    },

    createPopup : function()
    {
        this.datePicker = new DatePicker();

        this.popup = this.datePicker.create().hide().absolutize();

        this.field.insert({ after : this.popup });

        this.datePicker.onselect = function()
        {
            this.field.value = this.formatDate(this.datePicker.getDate());

            this.hidePopup();

            new Effect.Highlight(this.field);

        }.bind(this);
    },

    formatDate : function(date)
    {
        if (date == null) return "";

        // TODO: This needs to localize; currently its Americanized (MM/DD/YYYY).
        return (date.getMonth() + 1) + "/" + date.getDate() + "/" + date.getFullYear();
    },

    positionPopup : function()
    {
        var fieldPos = this.field.positionedOffset();

        var height = this.field.getHeight();

        this.popup.setStyle({ top: fieldPos.top + height + 2 + "px", left: fieldPos.left, width: "", height: "" });
    },

    hidePopup : function()
    {

        new Effect.Fade(this.popup, { duration: .20 });
    },

    revealPopup : function()
    {

        // Only show one DateField popup at a time.

        if (Tapestry.DateField.activeDateField != undefined &&
            Tapestry.DateField.activeDateField != this)
        {
            Tapestry.DateField.activeDateField.hidePopup();
        }

        new Effect.Appear(this.popup, { duration: .20 });

        Tapestry.DateField.activeDateField = this;
    }
};