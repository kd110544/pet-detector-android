package pp.petdetector;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

import pp.petdetector.tracking.MultiBoxTracker;

public class Act2 extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act2);

        TextView txtName = findViewById(R.id.txtName);
        txtName.setText("貓的品種是:" +MultiBoxTracker.callName());

        TextView txtAttribute = findViewById(R.id.txtAttribute);
        txtAttribute.setText("辨識準確程度:"+String.format("%.2f",MultiBoxTracker.callType()));

    }
}
